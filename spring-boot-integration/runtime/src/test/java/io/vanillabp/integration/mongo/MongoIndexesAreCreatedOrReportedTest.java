package io.vanillabp.integration.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.mongo.MongoIndex;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.delivery.MongoTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What <code>vanillabp.outbox.create-schema</code> does on MongoDB, against a database
 * rather than against a double: with the default the startup creates the indexes the
 * stores read by, and where the application manages its own schema it creates none and
 * names every one of them instead.
 * <p>
 * The delivery log is built here too, because its collection is the third one and it reads
 * the same property.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressBackgroundOutput
@Testcontainers
public class MongoIndexesAreCreatedOrReportedTest {

  private static final String DATABASE = "mongo-indexes-test";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  private MongoClient mongoClient;

  private MongoTemplate mongoTemplate;

  private final ListAppender<ILoggingEvent> whatWasLogged = new ListAppender<>();

  private Logger rootLog;

  /**
   * Kept because its startup starts a cleanup of its own, which has to be stopped again
   * when the test is over.
   */
  private MongoTaskDeliveryLog deliveryLog;

  @BeforeEach
  public void connectAndWatchTheLog() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    mongoTemplate = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, DATABASE));
    mongoTemplate
        .getDb()
        .drop();
    whatWasLogged.start();
    rootLog = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLog.addAppender(whatWasLogged);

  }

  @AfterEach
  public void disconnect() {

    if (deliveryLog != null) {
      deliveryLog.stop();
      deliveryLog = null;
    }
    rootLog.detachAppender(whatWasLogged);
    whatWasLogged.stop();
    mongoClient.close();

  }

  @Test
  @DisplayName("The default creates every index the stores read by")
  public void theDefaultCreatesEveryIndex() {

    buildTheStoresWith(propertiesCreatingTheSchema(true));

    assertIndexesOf(
        properties().getOutbox().getMongo().getCollection(),
        MongoSchema.OUTBOX_INDEXES);
    assertIndexesOf(
        properties().getOutbox().getMongo().payloadCollectionName(),
        MongoSchema.PAYLOAD_INDEXES);
    assertIndexesOf(
        properties().getOutbox().getMongo().getDeliveryCollection(),
        MongoSchema.DELIVERY_INDEXES);

  }

  @Test
  @DisplayName("An application managing its own schema gets no index and reads which ones it owes")
  public void anApplicationManagingItsOwnSchemaIsTold() {

    buildTheStoresWith(propertiesCreatingTheSchema(false));

    final var collections = new ArrayList<String>();
    mongoTemplate
        .getDb()
        .listCollectionNames()
        .forEach(collections::add);
    assertEquals(List.of(), collections, "nothing may be created where the application said so");

    final var reported = whatWasLogged.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains("createIndex"))
        .toList();
    assertEquals(3, reported.size(), "one message per collection: "
        + reported);
    assertTrue(
        reported
            .stream()
            .anyMatch(message -> message
                .contains(
                    "db.getCollection(\"vanillabp-phase-two-outbox\").createIndex({ \"dedupKey\": 1 }, { \"unique\": true });")),
        () -> "the statement a developer pastes is missing from: "
            + reported);
    assertTrue(
        reported
            .stream()
            .anyMatch(message -> message.contains("vanillabp-task-deliveries")),
        () -> "the delivery collection is the third one, and it owes its indexes too: "
            + reported);

  }

  @Test
  @DisplayName("Indexes the application created itself are not reported")
  public void whatTheApplicationCreatedItselfIsNotReported() {

    final var collection = properties()
        .getOutbox()
        .getMongo()
        .getCollection();
    MongoIndexes.createOn(mongoTemplate, collection, MongoSchema.OUTBOX_INDEXES);

    buildTheStoresWith(propertiesCreatingTheSchema(false));

    // the name of the payload collection starts with the name of the outbox one, so the
    // quotes are part of what is looked for here
    assertTrue(
        whatWasLogged.list
            .stream()
            .map(ILoggingEvent::getFormattedMessage)
            .noneMatch(message -> message.contains("'%s' is missing".formatted(collection))),
        () -> "the outbox collection carries everything, so nothing is owed for it: "
            + whatWasLogged.list);

  }

  /**
   * Builds what a Spring Boot application builds at startup: the outbox and the delivery
   * log, both of which look after the indexes of their collections.
   *
   * @param vanillaBpProperties The configuration they read
   */
  private void buildTheStoresWith(
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    // without a dispatcher: the outbox only keeps the one it is handed, and nothing here
    // schedules an entry
    new MongoPhaseTwoOutboxAutoConfiguration()
        .vanillaBpMongoPhaseTwoOutbox(mongoTemplate, null, vanillaBpProperties);
    final var deliveryLogAutoConfiguration = new MongoTaskDeliveryLogAutoConfiguration();
    deliveryLog = deliveryLogAutoConfiguration
        .vanillaBpMongoTaskDeliveryLog(mongoTemplate, vanillaBpProperties);
    deliveryLogAutoConfiguration
        .vanillaBpMongoTaskDeliveryLogStartup(mongoTemplate, deliveryLog, vanillaBpProperties)
        .afterSingletonsInstantiated();

  }

  /**
   * @param collection The collection to look at
   * @param needed The indexes it has to carry
   */
  private void assertIndexesOf(
      final String collection,
      final List<MongoIndex> needed) {

    final var keys = new ArrayList<Document>();
    mongoTemplate
        .getCollection(collection)
        .listIndexes()
        .forEach(index -> keys.add(index.get("key", Document.class)));
    needed
        .forEach(index -> assertTrue(
            keys.contains(keyOf(index)),
            () -> "the index over %s is missing from %s: %s".formatted(index.fields(), collection, keys)));

  }

  /**
   * @param index The index VanillaBP needs
   * @return The key document MongoDB reports for it
   */
  private static Document keyOf(
      final MongoIndex index) {

    final var key = new Document();
    index
        .fields()
        .forEach(field -> key.append(field, 1));
    return key;

  }

  /**
   * @return The configuration of an application which writes nothing about the outbox
   */
  private static VanillaBpConfigurationProperties properties() {

    return new VanillaBpConfigurationProperties();

  }

  /**
   * @param createSchema What <code>vanillabp.outbox.create-schema</code> says
   * @return The configuration
   */
  private static VanillaBpConfigurationProperties propertiesCreatingTheSchema(
      final boolean createSchema) {

    final var vanillaBpProperties = properties();
    vanillaBpProperties
        .getOutbox()
        .setCreateSchema(createSchema);
    return vanillaBpProperties;

  }

}
