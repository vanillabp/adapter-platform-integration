package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application with naming rules for its collections renames the delivery log in the
 * same way as the outbox: <code>vanillabp.outbox.mongo.delivery-collection</code>.
 * <p>
 * The test follows the name instead of reading it back. The record must land in the
 * configured collection, the indexes must be created on the same one, and the default
 * name must not exist at all. A wiring could read the property and then write to the
 * constant. It would pass a test which asserts the property, but it fails this one.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = {
        TestApplication.class, MongoDeliveryCollectionNameTest.RenamedCollectionTestConfiguration.class
    },
    properties = {
        "vanillabp.outbox.mongo.delivery-collection=deliveries-of-mine", "vanillabp.outbox.retention=PT24H"
    })
@Testcontainers
public class MongoDeliveryCollectionNameTest {

  private static final String RENAMED = "deliveries-of-mine";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class RenamedCollectionTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
    }

  }

  @Autowired
  private io.vanillabp.integration.delivery.MongoTaskDeliveryLog deliveryLog;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Test
  @DisplayName("The record is written into the configured collection, and the default one is never created")
  public void theRecordLandsInTheConfiguredCollection() {

    transactionTemplate.executeWithoutResult(
        status -> assertTrue(
            deliveryLog
                .record(
                    new TaskDelivery("job-1", "test-adapter", "test-module", "TestProcess", "4711", "workflow-4711", "processTask", "Activity_processTask", null, "COMPLETED", null, null, java.time.Instant
                        .now(), null))));

    assertEquals(
        1,
        mongoTemplate.getCollection(RENAMED).countDocuments(),
        "the document lies where the application asked for it");
    assertTrue(
        deliveryLog.recordedDelivery("job-1").isPresent(),
        "and the log reads it back out of the same collection");
    assertFalse(
        mongoTemplate.getCollectionNames().contains(
            PhaseTwoOutboxProperties.MongoOutboxProperties.DEFAULT_DELIVERY_COLLECTION),
        "nothing touched the default name, so no second collection was created");

  }

  @Test
  @DisplayName("The indexes are created on the configured collection")
  public void theIndexesFollowTheName() {

    final var indexedFields = mongoTemplate
        .indexOps(RENAMED)
        .getIndexInfo()
        .stream()
        .flatMap(index -> index.getIndexFields().stream())
        .map(field -> field.getKey())
        .collect(Collectors.toSet());

    assertTrue(
        indexedFields.containsAll(
            java.util.List.of("lastSeenAt", "taskId", "aggregateId", "workflowId")),
        "an index on a collection nobody writes to would cost the reads it was made for");

  }

}
