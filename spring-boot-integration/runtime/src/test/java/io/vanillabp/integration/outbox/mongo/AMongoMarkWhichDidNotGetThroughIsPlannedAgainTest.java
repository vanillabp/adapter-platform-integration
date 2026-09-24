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
import org.testcontainers.containers.wait.strategy.Wait;
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
 * What happens when the dispatch got through and the write which says so did not.
 * <p>
 * That write runs after the renewal of the lease was let go, so it stands outside the attempt
 * and outside the lane's own error handling. An exception there used to leave the thread of
 * the lane without a word, and the entry lay claimed until its lease ran out: the operation
 * had reached the BPMS and nothing in the log said what had become of it. It is reported and
 * planned again instead, the way any attempt which did not get through is.
 * <p>
 * The failing write is a real one, not a double: the unique index over <code>dedupKey</code>
 * is what a dispatched entry writes its own id into, so an entry whose id another document
 * already holds there cannot be marked. That is the same error a store hands back when two
 * nodes race, and it needs nothing mocked.
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
   * How long the entry waits before the next attempt, which is the distance after the first
   * failure. Long enough that exactly one attempt happens while this test looks.
   */
  private static final Duration BACKOFF = Duration.ofSeconds(30);

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

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
    // the index a running application creates with the collection, and the reason the mark of
    // this test cannot get through
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
   * One bean, as the dispatcher is handed it. A running application gets these from its
   * container; a test which builds the dispatcher itself has to say what they answer.
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
   * Writes the entry to be dispatched, and next to it a dispatched entry which already holds
   * that entry's id as its <code>dedupKey</code>. The mark of the first one therefore cannot
   * be written.
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
