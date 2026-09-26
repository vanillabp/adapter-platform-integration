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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;

import ch.qos.logback.classic.Level;
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
 * A dispatch which fails while the node is being stopped, and cannot write down that it
 * failed either.
 * <p>
 * The mark path of this store has been guarded since the store was written: what MongoDB
 * throws while an entry is marked done ends as a report. The failure path was not, and a
 * stop is exactly when it matters - the entry's next write runs into an interrupted
 * thread, MongoDB answers "Interrupted waiting for lock", and the exception leaves the
 * runnable of a lane. What an operator then reads is "Exception in thread
 * vanillabp-outbox-dispatch-1" and a stack trace with nothing around it, on a shutdown
 * where nothing is wrong.
 * <p>
 * The one write of this story is made to fail by a spy over the real template, and only
 * that write: an interruption cannot be produced by timing without making the test depend
 * on which of the driver's waits happens to be reached. Everything else - the entry, the
 * claim, the lanes, the poller - is the real store on a real MongoDB.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
@Testcontainers
public class AMongoDispatchInterruptedWhileStoppingTest {

  private static final Duration LEASE = Duration.ofSeconds(3);

  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String DATABASE = "interrupted-dispatch-test";

  private static final String COLLECTION = "outbox-interrupted-dispatch";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  // no wait strategy of its own: the one the container brings waits until the MAPPED port
  // accepts a connection, while a log line only says that mongod listens inside the container
  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB));

  @Mock
  private MigrationProcessService<Object> theNode;

  /**
   * What the driver answers a thread which was interrupted while it waited for a
   * connection. Spring Data wraps it, which is the shape the log of 2026-09-24 carried.
   */
  private static class InterruptedWhileWriting extends UncategorizedDataAccessException {

    private static final long serialVersionUID = 1L;

    InterruptedWhileWriting() {

      super("Interrupted waiting for lock", new InterruptedException("Interrupted waiting for lock"));

    }

  }

  private final CountDownLatch theDispatchWasTried = new CountDownLatch(1);

  /**
   * Set once the dispatch failed, so only the write which follows a failed dispatch is the
   * one made to fail. The claim, the renewal and the test's own writes go to the database.
   */
  private final AtomicBoolean theDispatchFailed = new AtomicBoolean();

  private final AtomicBoolean theWriteAlreadyFailed = new AtomicBoolean();

  private final ListAppender<ILoggingEvent> whatWasLogged = new ListAppender<>();

  private Logger dispatcherLog;

  private MongoTemplate mongoTemplate;

  private MongoClient mongoClient;

  /**
   * What left the thread of a lane, which is what this story is about.
   */
  private final java.util.List<Throwable> outOfALaneThread = java.util.Collections
      .synchronizedList(new java.util.ArrayList<>());

  private Thread.UncaughtExceptionHandler handlerOfThisJvm;

  @BeforeEach
  public void watchTheLogAndTheThreads() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    mongoTemplate = Mockito
        .spy(new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, DATABASE)));
    Mockito
        .doAnswer(write -> {
          if (theDispatchFailed.get() && theWriteAlreadyFailed.compareAndSet(false, true)) {
            throw new InterruptedWhileWriting();
          }
          return write.callRealMethod();
        })
        .when(mongoTemplate)
        .updateFirst(Mockito.any(Query.class), Mockito.any(Update.class), Mockito.anyString());

    whatWasLogged.start();
    dispatcherLog = (Logger) LoggerFactory.getLogger(MongoPhaseTwoOutboxDispatcher.class);
    dispatcherLog.addAppender(whatWasLogged);

    handlerOfThisJvm = Thread.getDefaultUncaughtExceptionHandler();
    Thread
        .setDefaultUncaughtExceptionHandler((
            thread,
            failure) -> outOfALaneThread.add(failure));

  }

  @AfterEach
  public void stopWatching() {

    Thread.setDefaultUncaughtExceptionHandler(handlerOfThisJvm);
    dispatcherLog.detachAppender(whatWasLogged);
    whatWasLogged.stop();
    mongoClient.close();

  }

  @Test
  @DisplayName("A stop during a dispatch leaves a line about a stop, and no stack trace out of a lane")
  public void aStopLeavesALineAndNoStackTrace() throws Exception {

    final var dispatcher = new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, theOnly(aRouterWhoseDispatchFails()), leasing(), COLLECTION, theOnly(VanillaBpMetrics.NONE));
    final var entry = anEntryDueNow();

    try {
      dispatcher.startPolling();
      assertTrue(
          theDispatchWasTried.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS),
          "the entry was never dispatched");
      waitUntil(
          "nothing was said about the entry whose result could not be written",
          () -> lineAbout(entry, "because this node is stopping") != null);
    } finally {
      dispatcher.stopPolling();
    }

    final var line = lineAbout(entry, "because this node is stopping");
    // a stop is the ordinary case, so it is said the way a stop is said elsewhere
    assertEquals(Level.INFO, line.getLevel());
    assertTrue(line.getFormattedMessage().contains("was interrupted"), line.getFormattedMessage());
    assertTrue(
        line.getFormattedMessage().contains("dispatched once its lease runs out"),
        line.getFormattedMessage());
    assertEquals(
        java.util.List.of(),
        outOfALaneThread,
        "an exception left the thread of a lane, where nobody can place it");

  }

  @Test
  @DisplayName("The entry stays as it was, so the next node picks it up")
  public void theEntryIsUntouched() throws Exception {

    final var dispatcher = new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, theOnly(aRouterWhoseDispatchFails()), leasing(), COLLECTION, theOnly(VanillaBpMetrics.NONE));
    final var entry = anEntryDueNow();

    try {
      dispatcher.startPolling();
      assertTrue(theDispatchWasTried.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS));
      waitUntil(
          "nothing was said about the entry whose result could not be written",
          () -> lineAbout(entry, "because this node is stopping") != null);
    } finally {
      dispatcher.stopPolling();
    }

    final var document = mongoTemplate
        .getCollection(COLLECTION)
        .find(Filters.eq("_id", entry))
        .first();
    assertNotNull(document, "the entry is gone");
    assertEquals(
        PhaseTwoOutboxEntry.STATUS_OPEN,
        document.getString("status"),
        "an entry whose result was never written stays open");

  }

  private ILoggingEvent lineAbout(
      final String entry,
      final String phrase) {

    return whatWasLogged.list
        .stream()
        .filter(event -> event.getFormattedMessage().contains(entry))
        .filter(event -> event.getFormattedMessage().contains(phrase))
        .findFirst()
        .orElse(null);

  }

  private PhaseTwoRouter aRouterWhoseDispatchFails() {

    when(theNode.getWorkflowModuleId()).thenReturn(MODULE);
    when(theNode.getBpmnProcessId()).thenReturn(PROCESS);
    when(theNode.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(dispatch -> {
          theDispatchFailed.set(true);
          theDispatchWasTried.countDown();
          throw new IllegalStateException("the BPMS did not answer");
        })
        .when(theNode)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(theNode);
    return router;

  }

  private static PhaseTwoOutboxProperties leasing() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setAttemptFrequency(LEASE);
    properties.setPollInterval(LEASE);
    return properties;

  }

  private String anEntryDueNow() {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    mongoTemplate
        .getCollection(COLLECTION)
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", PhaseOperation.START_WORKFLOW.name())
            .append("aggregateId", AGGREGATE)
            .append("adapterId", "dummy")
            .append("idempotencyKey", id)
            .append("dedupKey", id)
            .append("status", PhaseTwoOutboxEntry.STATUS_OPEN)
            .append("createdAt", now)
            .append("attempts", 0)
            .append("nextAttemptAt", now)
            .append("leasedBy", null)
            .append("leasedUntil", null));
    return id;

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

  /**
   * One bean, as the dispatcher is handed it.
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
      public T getObject(
          final Object... args) {

        return bean;

      }

      @Override
      public T getIfAvailable() {

        return bean;

      }

      @Override
      public T getIfUnique() {

        return bean;

      }

    };

  }

}
