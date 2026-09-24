package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLease;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What happens to a dispatch whose entry was taken over while it ran.
 * <p>
 * The renewal notices it - the write matches no row - and the dispatch which lost the entry
 * runs to its end. What it must NOT do is write down how that ended, because the node
 * holding the entry now is doing the same operation and its answer is the one the table
 * keeps. A late "blocked" over a finished entry would be the worst of them: the operation
 * reached the BPMS and somebody would be asked to repair it by hand.
 * <p>
 * Two dispatchers on one database are what this needs, and one of them is robbed of its
 * lease by an <code>UPDATE</code> of the test, which is a node whose claim ran out while it
 * was working. Playing the second node with a mock would prove nothing here: the write
 * which is refused is the one both nodes really run.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class ADispatchWhichLostItsLeaseTest {

  /**
   * How long a claim lasts here. Short, so the node which is robbed of its entry notices it
   * within a tick instead of at the end of a distance chosen for production. Not shorter,
   * because the poller of that node sleeps until its own claim runs out: everything below
   * happens within one lease, and the node which lost the entry never gets to poll for it
   * again.
   */
  private static final Duration LEASE = Duration.ofSeconds(3);

  /**
   * How long a test waits for something it expects to happen. It is a guard against a
   * machine which leaves the JVM without a turn, not a measurement of speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT, LEASED_BY, LEASED_UNTIL) \
      VALUES (?, '%s', '%s', ?, '%s', 'dummy', NULL, NULL, ?, '%s', ?, 0, ?, NULL, NULL)""";

  private static final String SELECT_ENTRY = """
      SELECT STATUS, ATTEMPTS, DONE_AT FROM %s WHERE ID = ?""";

  /**
   * What the test does to the entry of the node which is still working on it: the claim is
   * released and the entry is due again, which is the row a node whose lease ran out leaves
   * behind.
   */
  private static final String FREE_THE_LEASE = """
      UPDATE %s SET LEASED_BY = NULL, LEASED_UNTIL = NULL, NEXT_ATTEMPT_AT = ? WHERE ID = ?""";

  @Mock
  private MigrationProcessService<Object> theNodeLosingTheEntry;

  @Mock
  private MigrationProcessService<Object> theNodeTakingTheEntry;

  /**
   * Released by the test so the dispatch which lost its entry can end. Every test lets go
   * of it, because that thread would otherwise wait for the whole run.
   */
  private final CountDownLatch letTheSlowDispatchEnd = new CountDownLatch(1);

  /**
   * Counted down by the slow node as its dispatch starts, so the test can take the entry
   * away while that dispatch is really running.
   */
  private final CountDownLatch theSlowDispatchStarted = new CountDownLatch(1);

  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  /**
   * What both dispatchers wrote into the log, which is where an operator reads that an
   * entry changed hands. The two classes log it, so both are watched.
   */
  private final ListAppender<ILoggingEvent> whatWasLogged = new ListAppender<>();

  private Logger dispatcherLog;

  private Logger leaseLog;

  @BeforeEach
  public void watchTheLog() {

    whatWasLogged.start();
    dispatcherLog = (Logger) LoggerFactory.getLogger(JdbcPhaseTwoOutboxDispatcher.class);
    leaseLog = (Logger) LoggerFactory.getLogger(DispatchLease.class);
    dispatcherLog.addAppender(whatWasLogged);
    leaseLog.addAppender(whatWasLogged);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    dispatcherLog.detachAppender(whatWasLogged);
    leaseLog.detachAppender(whatWasLogged);
    whatWasLogged.stop();

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
   * A database per test, because every test of this class writes into the outbox table and
   * reads what is in it afterwards.
   */
  private static JdbcConnectionAccess databaseNamed(
      final String name) {

    return () -> DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  private static PhaseTwoOutboxProperties leasingFor(
      final Duration lease) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setAttemptFrequency(lease);
    // the poll interval is the cap on the sleep, and a short one is what lets the second
    // node find the entry as soon as the test released it
    properties.setPollInterval(lease);
    return properties;

  }

  /**
   * The node whose entry is taken away: its dispatch waits until the test lets it end, and
   * then ends the way the test asked for.
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
   * The node which takes the entry over: its dispatch succeeds at once.
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

  private JdbcPhaseTwoOutboxDispatcher dispatcherOf(
      final JdbcConnectionAccess connections,
      final PhaseTwoRouter router,
      final String table) {

    return new JdbcPhaseTwoOutboxDispatcher(
        connections, leasingFor(
            LEASE), table, new JdbcPhaseTwoPayloadStore(connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX), () -> router, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");

  }

  private String anEntryDueNow(
      final JdbcConnectionAccess connections,
      final String table) throws SQLException {

    final var id = UUID.randomUUID().toString();
    final var now = Instant.now();
    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(
            INSERT_ENTRY
                .formatted(table, MODULE, PROCESS, AGGREGATE, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN))) {
      statement.setString(1, id);
      statement.setString(2, PhaseOperation.START_WORKFLOW.name());
      statement.setString(3, id);
      statement.setTimestamp(4, Timestamp.from(now));
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
    return id;

  }

  /**
   * Takes the claim off the entry, which is what a node whose lease ran out leaves behind.
   */
  private void freeTheLeaseOf(
      final JdbcConnectionAccess connections,
      final String table,
      final String id) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(FREE_THE_LEASE.formatted(table))) {
      statement.setTimestamp(1, Timestamp.from(Instant.now()));
      statement.setString(2, id);
      assertEquals(1, statement.executeUpdate(), "the entry whose lease was to be freed is gone");
    }

  }

  private Entry entryOf(
      final JdbcConnectionAccess connections,
      final String table,
      final String id) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(SELECT_ENTRY.formatted(table))) {
      statement.setString(1, id);
      try (var resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "the entry is gone");
        final var doneAt = resultSet.getTimestamp(3);
        return new Entry(resultSet.getString(1), resultSet.getInt(2), doneAt == null ? null : doneAt.toInstant());
      }
    }

  }

  private record Entry(String status, int attempts, Instant doneAt) {
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
   * Runs the story both tests share: the first node takes the entry and holds it, the test
   * frees the lease, the second node dispatches the entry to its end, and then the first
   * node's dispatch is allowed to finish.
   *
   * @param database Which database this test works on
   * @param table The outbox table of this test
   * @param theSlowDispatchEndsWith What the first node's dispatch throws, or
   *          <code>null</code> where it succeeds
   * @return The entry as the table showed it when the second node was done with it
   */
  private Entry theEntryChangesHands(
      final String database,
      final String table,
      final RuntimeException theSlowDispatchEndsWith) throws Exception {

    final var connections = databaseNamed(database);
    final var losingNode = dispatcherOf(connections, aRouterWhoseDispatchWaits(theSlowDispatchEndsWith), table);
    final var takingNode = dispatcherOf(connections, aRouterWhoseDispatchSucceeds(), table);
    losingNode.prepareSchema();
    final var entry = anEntryDueNow(connections, table);

    try {
      losingNode.start();
      assertTrue(
          theSlowDispatchStarted.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS),
          "the entry was never dispatched");
      freeTheLeaseOf(connections, table, entry);

      takingNode.start();
      waitUntil(
          "the second node never took the entry over",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(entryOf(connections, table, entry).status()));
      final var afterTheSecondNode = entryOf(connections, table, entry);

      // the renewal is what notices, so the first node has to still be dispatching when it
      // ticks. Letting its dispatch end first would cancel the tick and nothing would say
      // that two nodes carried the operation out
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
          entryOf(connections, table, entry),
          "the node which lost the entry wrote its result over the one of the node holding it");
      return afterTheSecondNode;
    } finally {
      letTheSlowDispatchEnd.countDown();
      takingNode.stop();
      losingNode.stop();
    }

  }

  @Test
  @DisplayName("A dispatch which lost its entry does not write its success over the one which took it")
  public void aLostEntryKeepsWhatTheOtherNodeWrote() throws Exception {

    final var afterTheSecondNode = theEntryChangesHands(
        "a-lost-entry-keeps-what-the-other-node-wrote", "OUTBOX_LOST_ENTRY_SUCCESS", null);

    assertEquals(
        1,
        afterTheSecondNode.attempts(),
        "one attempt ended on the entry of the node which held it, so one is what it counts");

  }

  @Test
  @DisplayName("A dispatch which lost its entry does not block an entry somebody else finished")
  public void aLostEntryIsNotBlockedByTheNodeWhichLostIt() throws Exception {

    // the failure which blocks an entry with one attempt - the write which would be the
    // most expensive of all to land on an entry the other node has just finished
    final var afterTheSecondNode = theEntryChangesHands(
        "a-lost-entry-is-not-blocked", "OUTBOX_LOST_ENTRY_BLOCKED", new PhaseTwoPermanentFailure(
            "the adapter says repeating cannot help", null));

    assertEquals(
        JdbcPhaseTwoOutboxDispatcher.STATUS_DONE,
        afterTheSecondNode.status(),
        "the entry the second node dispatched was blocked by the first one");

  }

}
