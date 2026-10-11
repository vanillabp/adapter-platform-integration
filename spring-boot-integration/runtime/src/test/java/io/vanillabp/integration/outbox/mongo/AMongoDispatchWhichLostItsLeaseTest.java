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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLease;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What happens to a dispatch of the MongoDB store when another node takes over its entry
 * while it runs.
 * <p>
 * The renewal notices it, because its write matches no document. The dispatch which lost the
 * entry still runs to its end. But it must NOT write down how it ended. The node which holds
 * the entry now runs the same operation, and the collection keeps that node's answer. The
 * worst case would be a late "blocked" over a finished entry. The operation reached the BPMS,
 * and still somebody would be asked to repair it by hand.
 * <p>
 * So the test needs two dispatchers on one database. A write of the test takes the lease away
 * from one of them. This plays a node whose claim ran out while it was working. A mock for the
 * second node would prove nothing here, because the refused write is the one both nodes really
 * run.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
@Testcontainers
public class AMongoDispatchWhichLostItsLeaseTest {

  /**
   * How long a claim lasts here. It is short, so the node which loses its entry notices it
   * within one tick, and not after a time chosen for production. It is not shorter, because the
   * poller of that node sleeps until its own claim runs out. Everything below happens within
   * one lease, so the node which lost the entry never polls for it again.
   */
  private static final Duration LEASE = Duration.ofSeconds(3);

  /**
   * How long a test waits for something it expects to happen. This guards against a busy
   * machine which gives the JVM no time. It does not measure speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String DATABASE = "lost-lease-test";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  // no wait strategy of its own: the container's own strategy waits until the MAPPED port
  // accepts a connection. A log line only says that mongod listens inside the container
  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB));

  @Mock
  private MigrationProcessService<Object> theNodeLosingTheEntry;

  @Mock
  private MigrationProcessService<Object> theNodeTakingTheEntry;

  /**
   * The test releases it, so the dispatch which lost its entry can end. Every test releases
   * it. Otherwise that thread would wait for the whole run.
   */
  private final CountDownLatch letTheSlowDispatchEnd = new CountDownLatch(1);

  /**
   * The slow node counts it down when its dispatch starts. So the test can take the entry away
   * while that dispatch really runs.
   */
  private final CountDownLatch theSlowDispatchStarted = new CountDownLatch(1);

  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  /**
   * What both dispatchers wrote into the log. An operator reads there that an entry changed
   * hands. Two classes log it, so the test watches both.
   */
  private final ListAppender<ILoggingEvent> whatWasLogged = new ListAppender<>();

  private Logger dispatcherLog;

  private Logger leaseLog;

  private MongoTemplate mongoTemplate;

  private MongoClient mongoClient;

  @BeforeEach
  public void watchTheLog() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    mongoTemplate = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, DATABASE));
    whatWasLogged.start();
    dispatcherLog = (Logger) LoggerFactory.getLogger(MongoPhaseTwoOutboxDispatcher.class);
    leaseLog = (Logger) LoggerFactory.getLogger(DispatchLease.class);
    dispatcherLog.addAppender(whatWasLogged);
    leaseLog.addAppender(whatWasLogged);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    dispatcherLog.detachAppender(whatWasLogged);
    leaseLog.detachAppender(whatWasLogged);
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

  private static PhaseTwoOutboxProperties leasingFor(
      final Duration lease) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setAttemptFrequency(lease);
    // the poll interval caps the sleep. A short one lets the second node find the entry as
    // soon as the test has released it
    properties.setPollInterval(lease);
    return properties;

  }

  /**
   * The node whose entry is taken away. Its dispatch waits until the test lets it end. Then
   * it ends the way the test asked for.
   *
   * @param endsWith What the dispatch throws, or <code>null</code> where it succeeds
   * @return The router of that node
   */
  private PhaseTwoRouter aRouterWhoseDispatchWaits(
      final RuntimeException endsWith) {

    answersFor(theNodeLosingTheEntry);
    Mockito
        .doAnswer(invocation -> {
          callsIntoTheAdapter.incrementAndGet();
          theSlowDispatchStarted.countDown();
          letTheSlowDispatchEnd.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS);
          if (endsWith != null) {
            throw endsWith;
          }
          return null;
        })
        .when(theNodeLosingTheEntry)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(theNodeLosingTheEntry);
    return router;

  }

  /**
   * The node which takes the entry over. Its dispatch succeeds at once.
   *
   * @return The router of that node
   */
  private PhaseTwoRouter aRouterWhoseDispatchSucceeds() {

    answersFor(theNodeTakingTheEntry);
    Mockito
        .doAnswer(invocation -> {
          callsIntoTheAdapter.incrementAndGet();
          return null;
        })
        .when(theNodeTakingTheEntry)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(theNodeTakingTheEntry);
    return router;

  }

  private static void answersFor(
      final MigrationProcessService<Object> node) {

    when(node.getWorkflowModuleId()).thenReturn(MODULE);
    when(node.getBpmnProcessId()).thenReturn(PROCESS);
    when(node.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);

  }

  private MongoPhaseTwoOutboxDispatcher dispatcherOf(
      final PhaseTwoRouter router,
      final String collection) {

    return new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, theOnly(router), leasingFor(LEASE), collection, theOnly(VanillaBpMetrics.NONE));

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

  /**
   * Writes an entry the way a crashed node leaves it behind. Nobody holds it and it is due, so
   * only a poll can pick it up.
   *
   * @param collection The collection of this test
   * @return The entry's id
   */
  private String anEntryDueNow(
      final String collection) {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    mongoTemplate
        .getCollection(collection)
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

  /**
   * Takes the claim off the entry. A node whose lease ran out leaves the entry like this.
   *
   * @param collection The collection of this test
   * @param id The entry to free
   */
  private void freeTheLeaseOf(
      final String collection,
      final String id) {

    final var freed = mongoTemplate
        .getCollection(collection)
        .updateOne(
            Filters.eq("_id", id),
            Updates
                .combine(
                    Updates.set("leasedBy", null),
                    Updates.set("leasedUntil", null),
                    Updates.set("nextAttemptAt", Date.from(Instant.now()))));
    assertEquals(1, freed.getMatchedCount(), "the entry whose lease was to be freed is gone");

  }

  private Entry entryOf(
      final String collection,
      final String id) {

    final var document = mongoTemplate
        .getCollection(collection)
        .find(Filters.eq("_id", id))
        .first();
    assertNotNull(document, "the entry is gone");
    return new Entry(
        document.getString("status"), document.getInteger("attempts"), document.getDate("doneAt"));

  }

  private record Entry(String status, int attempts, Date doneAt) {
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
   * Runs the story both tests share. The first node takes the entry and holds it. The test
   * frees the lease. The second node dispatches the entry to its end. Then the test lets the
   * first node's dispatch finish.
   *
   * @param collection The outbox collection of this test
   * @param theSlowDispatchEndsWith What the first node's dispatch throws, or
   *          <code>null</code> where it succeeds
   * @return The entry as the collection showed it when the second node was done with it
   */
  private Entry theEntryChangesHands(
      final String collection,
      final RuntimeException theSlowDispatchEndsWith) throws Exception {

    final var losingNode = dispatcherOf(aRouterWhoseDispatchWaits(theSlowDispatchEndsWith), collection);
    final var takingNode = dispatcherOf(aRouterWhoseDispatchSucceeds(), collection);
    final var entry = anEntryDueNow(collection);

    try {
      losingNode.startPolling();
      assertTrue(
          theSlowDispatchStarted.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS),
          "the entry was never dispatched");
      freeTheLeaseOf(collection, entry);

      takingNode.startPolling();
      waitUntil(
          "the second node never took the entry over",
          () -> PhaseTwoOutboxEntry.STATUS_DONE.equals(entryOf(collection, entry).status()));
      final var afterTheSecondNode = entryOf(collection, entry);

      // the renewal notices the loss, so the first node must still be dispatching when the
      // renewal ticks. If its dispatch ended first, the tick would be canceled. Then nothing
      // would say that two nodes ran the operation
      waitUntil(
          "the node which lost its lease never noticed",
          () -> somethingWasLoggedAbout(entry, "was lost while it was being dispatched"));
      letTheSlowDispatchEnd.countDown();
      waitUntil(
          "the node which lost the entry never said what became of its result",
          () -> somethingWasLoggedAbout(entry, "belongs to another node"));

      assertEquals(2, callsIntoTheAdapter.get(), "the operation did not reach the adapter twice");
      assertEquals(
          afterTheSecondNode,
          entryOf(collection, entry),
          "the node which lost the entry wrote its result over the one of the node holding it");
      return afterTheSecondNode;
    } finally {
      letTheSlowDispatchEnd.countDown();
      takingNode.stopPolling();
      losingNode.stopPolling();
    }

  }

  @Test
  @DisplayName("A dispatch which lost its entry does not write its success over the one which took it")
  public void aLostEntryKeepsWhatTheOtherNodeWrote() throws Exception {

    final var afterTheSecondNode = theEntryChangesHands("outbox-lost-entry-success", null);

    assertEquals(
        1,
        afterTheSecondNode.attempts(),
        "one attempt ended on the entry of the node which held it, so one is what it counts");

  }

  @Test
  @DisplayName("A dispatch which lost its entry does not block an entry somebody else finished")
  public void aLostEntryIsNotBlockedByTheNodeWhichLostIt() throws Exception {

    // this failure blocks an entry after one attempt. On an entry which the other node has
    // just finished, this write would cost the most
    final var afterTheSecondNode = theEntryChangesHands(
        "outbox-lost-entry-blocked", new PhaseTwoPermanentFailure(
            "the adapter says repeating cannot help", null));

    assertEquals(
        PhaseTwoOutboxEntry.STATUS_DONE,
        afterTheSecondNode.status(),
        "the entry the second node dispatched was blocked by the first one");

  }

}
