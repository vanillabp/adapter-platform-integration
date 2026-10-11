package io.vanillabp.integration.outbox.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What happens when the dispatch got through but the write which says so did not.
 * <p>
 * That write runs after the renewal of the lease has stopped. So it is outside the attempt and
 * outside the lane's own error handling. An exception there used to leave the thread of the
 * lane without a word. The entry stayed claimed until its lease ran out. The operation had
 * reached the BPMS, and nothing in the log said what had become of it. Now the failure is
 * reported and the entry is planned again, like any other attempt which did not get through.
 * <p>
 * The failing write is a real one, not a test double. A dispatched entry writes its own id
 * into <code>dedupKey</code>, which has a unique index. If another document already holds the
 * id there, the entry cannot be marked. A store gives the same error when two nodes race, and
 * the test needs no mock for it.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
@Testcontainers
public class AMongoMarkWhichDidNotGetThroughIsPlannedAgainTest {

  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String DATABASE = "mark-which-failed-test";

  private static final String COLLECTION = "outbox-mark-which-failed";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  /**
   * How long the entry waits after the first failure before the next attempt. It is long
   * enough that exactly one attempt happens while this test looks.
   */
  private static final Duration BACKOFF = Duration.ofSeconds(30);

  // no wait strategy of its own: the container's own strategy waits until the MAPPED port
  // accepts a connection. A log line only says that mongod listens inside the container
  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB));

  @Mock
  private MigrationProcessService<Object> node;

  private final ListAppender<ILoggingEvent> whatWasLogged = new ListAppender<>();

  private Logger dispatcherLog;

  private MongoTemplate mongoTemplate;

  private MongoClient mongoClient;

  private MongoPhaseTwoOutboxDispatcher dispatcher;

  @BeforeEach
  public void connectAndWatchTheLog() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    mongoTemplate = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, DATABASE));
    mongoTemplate.getCollection(COLLECTION).deleteMany(new Document());
    // a running application creates this index with the collection. It is the reason why the
    // mark of this test cannot get through
    mongoTemplate
        .indexOps(COLLECTION)
        .createIndex(new Index()
            .on("dedupKey", Sort.Direction.ASC)
            .unique());
    whatWasLogged.start();
    dispatcherLog = (Logger) LoggerFactory.getLogger(MongoPhaseTwoOutboxDispatcher.class);
    dispatcherLog.addAppender(whatWasLogged);

  }

  @AfterEach
  public void stopEverything() {

    if (dispatcher != null) {
      dispatcher.stopPolling();
      dispatcher = null;
    }
    dispatcherLog.detachAppender(whatWasLogged);
    whatWasLogged.stop();
    mongoClient.close();

  }

  private boolean somethingWasLoggedAbout(
      final String entry,
      final String phrase) {

    return whatWasLogged.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains(entry) && message.contains(phrase));

  }

  /**
   * One bean, in the form the dispatcher gets it. A running application gets these providers
   * from its container. A test which builds the dispatcher itself has to say what they answer.
   *
   * @param <T> What the provider stands for
   * @param bean The bean to hand out
   * @return A provider answering with that bean
   */
  private static <T> ObjectProvider<T> theOnly(
      final T bean) {

    return new ObjectProvider<T>() {

      @Override
      public T getObject() {

        return bean;

      }

      @Override
      public Stream<T> stream() {

        return Stream.of(bean);

      }

    };

  }

  private void aDispatcherWhoseDispatchSucceeds() {

    when(node.getWorkflowModuleId()).thenReturn(MODULE);
    when(node.getBpmnProcessId()).thenReturn(PROCESS);
    when(node.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    final var router = new PhaseTwoRouter();
    router.register(node);
    final var properties = new PhaseTwoOutboxProperties();
    properties.setPollInterval(Duration.ofSeconds(1));
    properties.setAttemptFrequency(BACKOFF);
    dispatcher = new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, theOnly(router), properties, COLLECTION, theOnly(VanillaBpMetrics.NONE));

  }

  /**
   * Writes the entry to be dispatched. Next to it, it writes a dispatched entry which already
   * holds that entry's id as its <code>dedupKey</code>. So the mark of the first one cannot be
   * written.
   *
   * @return The id of the entry to be dispatched
   */
  private String anEntryWhoseMarkCannotBeWritten() {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    mongoTemplate
        .getCollection(COLLECTION)
        .insertOne(new Document()
            .append("_id", UUID.randomUUID().toString())
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", PhaseOperation.START_WORKFLOW.name())
            .append("aggregateId", "another-aggregate")
            .append("adapterId", "dummy")
            .append("dedupKey", id)
            .append("status", PhaseTwoOutboxEntry.STATUS_DONE)
            .append("createdAt", now)
            .append("doneAt", now)
            .append("attempts", 1));
    mongoTemplate
        .getCollection(COLLECTION)
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", PhaseOperation.START_WORKFLOW.name())
            .append("aggregateId", AGGREGATE)
            .append("adapterId", "dummy")
            .append("idempotencyKey", "the-key-of-"
                + id)
            .append("dedupKey", "the-key-of-"
                + id)
            .append("status", PhaseTwoOutboxEntry.STATUS_OPEN)
            .append("createdAt", now)
            .append("attempts", 0)
            .append("nextAttemptAt", now)
            .append("leasedBy", null)
            .append("leasedUntil", null));
    return id;

  }

  private Document entryOf(
      final String id) {

    final var entry = mongoTemplate
        .getCollection(COLLECTION)
        .find(Filters.eq("_id", id))
        .first();
    assertNotNull(entry, "the entry is gone");
    return entry;

  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A mark which did not get through is reported and the entry is planned again")
  public void aMarkWhichDidNotGetThroughIsPlannedAgain() throws Exception {

    final var entry = anEntryWhoseMarkCannotBeWritten();
    aDispatcherWhoseDispatchSucceeds();

    dispatcher.startPolling();
    waitUntil(
        "the entry whose mark could not be written was never planned again",
        () -> entryOf(entry).getInteger("attempts") == 1);

    final var afterTheAttempt = entryOf(entry);
    assertEquals(
        PhaseTwoOutboxEntry.STATUS_OPEN,
        afterTheAttempt.getString("status"),
        "an entry whose mark did not get through is still waiting for one");
    assertTrue(
        afterTheAttempt.getDate("nextAttemptAt").toInstant().isAfter(Instant.now()),
        "the entry was not planned for a later moment");
    assertTrue(
        somethingWasLoggedAbout(entry, "is dispatched again in"),
        "nothing said that the mark did not get through");

  }

}
