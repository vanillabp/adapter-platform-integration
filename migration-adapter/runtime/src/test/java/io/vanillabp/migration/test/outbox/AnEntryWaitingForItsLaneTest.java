package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What a claimed entry does while it waits for the thread which dispatches its aggregate.
 * <p>
 * The poller claims an entry and hands it to a lane, and the lane may be busy with an
 * earlier entry of the same aggregate. Two things used to go wrong in that wait. The lease
 * of the waiting entry was not renewed, so a wait longer than one
 * <code>attempt-frequency</code> let another node claim it and both nodes carried the same
 * operation out. And the poller held the connection it claimed with while it waited for a
 * full lane, which is the connection the lane needs to write down how its dispatch ended.
 * <p>
 * The tests below hold both: the lease of a waiting entry moves, another node leaves such
 * an entry alone, and a lane which is full does not hold up the dispatch which would empty
 * it.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class AnEntryWaitingForItsLaneTest {

  /**
   * How long a claim lasts here. Short enough that the wait in the queue below outlasts it
   * several times over, which is what made the defect visible.
   */
  private static final Duration LEASE = Duration.ofMillis(400);

  /**
   * The lease of the test which watches the lease move. The renewal pushes it along after a
   * third of it and a claim only after all of it, so the two are told apart by the moment
   * the entry changes - and that needs a lease long enough that a machine under load does
   * not decide the answer.
   */
  private static final Duration A_LEASE_LONG_ENOUGH_TO_WATCH = Duration.ofMillis(1500);

  /**
   * How long a test waits for something it expects to happen. It is a guard against a
   * machine which leaves the JVM without a turn, not a measurement of speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  /**
   * How many entries of one aggregate are written where the test wants a lane whose queue
   * is full. More than one queue holds, so the poller reaches an entry it cannot hand over
   * and has to wait.
   */
  private static final int MORE_THAN_ONE_QUEUE_HOLDS = 40;

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  /**
   * The aggregate of the entry the second node writes for itself. A different one, so its
   * entry never queues behind what the first node is holding.
   */
  private static final String ANOTHER_AGGREGATE = "43";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT, LEASED_BY, LEASED_UNTIL) \
      VALUES (?, '%s', '%s', ?, ?, 'dummy', NULL, NULL, ?, '%s', ?, 0, ?, NULL, NULL)""";

  private static final String SELECT_ENTRY = """
      SELECT STATUS, ATTEMPTS, LEASED_BY, LEASED_UNTIL FROM %s WHERE ID = ?""";

  @Mock
  private MigrationProcessService<Object> theHoldingNode;

  @Mock
  private MigrationProcessService<Object> theOtherNode;

  /**
   * Released by the test so the dispatch which holds a lane can end. Every test lets go of
   * it, because the lane thread would otherwise wait for the whole run.
   */
  private final CountDownLatch letTheLaneGo = new CountDownLatch(1);

  /**
   * Counted down by the first call into the adapter, so a test can wait for the dispatch to
   * have STARTED rather than sleeping for a while.
   */
  private final CountDownLatch theFirstCallStarted = new CountDownLatch(1);

  private final AtomicInteger callsIntoTheOtherNode = new AtomicInteger();

  /**
   * A database per test, because every test of this class writes into the outbox table and
   * reads what is in it afterwards.
   *
   * @param name The database this test works on
   * @return Connections to it, as many at a time as anybody asks for
   */
  private static JdbcConnectionAccess databaseNamed(
      final String name) {

    return () -> DriverManager.getConnection(urlOf(name), "sa", "");

  }

  /**
   * The same database, handing out ONE connection at a time. It is what a pool of one
   * connection does, and it turns "somebody holds a connection somebody else needs" from a
   * delay into a test which ends.
   *
   * @param name The database this test works on
   * @return Connections to it, one at a time
   */
  private static JdbcConnectionAccess databaseNamedWithOneConnection(
      final String name) {

    final var freeConnection = new Semaphore(1);
    return new JdbcConnectionAccess() {

      @Override
      public Connection acquire() throws SQLException {

        try {
          freeConnection.acquire();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SQLException("waiting for the one connection was interrupted", e);
        }
        return DriverManager.getConnection(urlOf(name), "sa", "");

      }

      @Override
      public void release(
          final Connection connection) throws SQLException {

        try {
          connection.close();
        } finally {
          freeConnection.release();
        }

      }

    };

  }

  private static String urlOf(
      final String name) {

    return "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name);

  }

  /**
   * The properties of a store with one lane, so every entry of the test meets the same
   * queue, and with a lease short enough that a wait in that queue outlasts it.
   *
   * @param lease How long a claim lasts
   * @return The properties of such a store
   */
  private static PhaseTwoOutboxProperties oneLaneLeasingFor(
      final Duration lease) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setAttemptFrequency(lease);
    // the poll interval is the cap on the sleep, and a short one is what makes the poll
    // happen again while the entries below are still waiting
    properties.setPollInterval(lease);
    properties.setDispatchThreads(1);
    return properties;

  }

  /**
   * A router whose adapter holds the lane: the first call waits until the test lets go, so
   * every entry handed in after it waits in the queue.
   *
   * @return The router of the node holding the entries
   */
  private PhaseTwoRouter aRouterWhoseAdapterHoldsTheLane() {

    answersFor(theHoldingNode, AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          theFirstCallStarted.countDown();
          letTheLaneGo.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS);
          return null;
        })
        .when(theHoldingNode)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(theHoldingNode);
    return router;

  }

  /**
   * A router whose adapter returns at once and counts how often it was called. It is the
   * second node, and the number is what says whether it took an entry of the first.
   *
   * @return The router of the second node
   */
  private PhaseTwoRouter aRouterOfASecondNode() {

    answersFor(theOtherNode, AGGREGATE);
    when(theOtherNode.convertAggregateId(ANOTHER_AGGREGATE)).thenReturn(ANOTHER_AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          callsIntoTheOtherNode.incrementAndGet();
          return null;
        })
        .when(theOtherNode)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(theOtherNode);
    return router;

  }

  /**
   * A router whose adapter returns at once and is not asked to count anything.
   *
   * @return The router
   */
  private PhaseTwoRouter aRouterWhoseAdapterReturnsAtOnce() {

    answersFor(theHoldingNode, AGGREGATE);
    final var router = new PhaseTwoRouter();
    router.register(theHoldingNode);
    return router;

  }

  private static void answersFor(
      final MigrationProcessService<Object> node,
      final String aggregate) {

    when(node.getWorkflowModuleId()).thenReturn(MODULE);
    when(node.getBpmnProcessId()).thenReturn(PROCESS);
    when(node.convertAggregateId(aggregate)).thenReturn(aggregate);

  }

  private JdbcPhaseTwoOutboxDispatcher dispatcherOf(
      final JdbcConnectionAccess connections,
      final PhaseTwoOutboxProperties properties,
      final PhaseTwoRouter router,
      final String table) {

    final var payloads = new JdbcPhaseTwoPayloadStore(
        connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX);
    return new JdbcPhaseTwoOutboxDispatcher(
        connections, properties, table, payloads, () -> router, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");

  }

  /**
   * Writes an entry which is due now.
   *
   * @param connections The database to write into
   * @param table The outbox table
   * @param aggregate Which workflow the entry belongs to
   * @param writtenAt When the entry was written, which is the order the poller claims in
   * @return The entry's id
   */
  private String anEntryDueNow(
      final JdbcConnectionAccess connections,
      final String table,
      final String aggregate,
      final Instant writtenAt) throws SQLException {

    final var id = UUID.randomUUID().toString();
    final var connection = connections.acquire();
    try (var statement = connection
        .prepareStatement(
            INSERT_ENTRY.formatted(table, MODULE, PROCESS, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN))) {
      statement.setString(1, id);
      statement.setString(2, PhaseOperation.START_WORKFLOW.name());
      statement.setString(3, aggregate);
      statement.setString(4, id);
      statement.setTimestamp(5, Timestamp.from(writtenAt));
      statement.setTimestamp(6, Timestamp.from(writtenAt));
      statement.executeUpdate();
    } finally {
      connections.release(connection);
    }
    return id;

  }

  /**
   * Writes the given number of entries of one aggregate, each one written a moment after
   * the one before it, so the poller claims them in the order they are returned.
   *
   * @return The ids, oldest first
   */
  private List<String> entriesOfOneAggregate(
      final JdbcConnectionAccess connections,
      final String table,
      final int count) throws SQLException {

    final var ids = new ArrayList<String>();
    final var firstOne = Instant.now().minusSeconds(count);
    for (var entry = 0; entry < count; entry++) {
      ids.add(anEntryDueNow(connections, table, AGGREGATE, firstOne.plusSeconds(entry)));
    }
    return ids;

  }

  /**
   * @return The entry as the table shows it now
   */
  private Entry entryOf(
      final JdbcConnectionAccess connections,
      final String table,
      final String id) throws SQLException {

    final var connection = connections.acquire();
    try (var statement = connection.prepareStatement(SELECT_ENTRY.formatted(table))) {
      statement.setString(1, id);
      try (var resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "the entry is gone");
        final var leasedUntil = resultSet.getTimestamp(4);
        return new Entry(
            resultSet.getString(1), resultSet.getInt(2), resultSet.getString(3), leasedUntil == null
                ? null
                : leasedUntil.toInstant());
      }
    } finally {
      connections.release(connection);
    }

  }

  /**
   * Whether every one of the given entries is DONE.
   */
  private boolean allDispatched(
      final JdbcConnectionAccess connections,
      final String table,
      final List<String> entries) throws SQLException {

    for (final var entry : entries) {
      if (!JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(entryOf(connections, table, entry).status())) {
        return false;
      }
    }
    return true;

  }

  private record Entry(String status, int attempts, String leasedBy, Instant leasedUntil) {
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
  @DisplayName("An entry waiting for its lane has its lease pushed along")
  public void aWaitingEntryKeepsItsLease() throws Exception {

    final var table = "OUTBOX_WAITING_ENTRY_KEEPS_ITS_LEASE";
    final var connections = databaseNamed("a-waiting-entry-keeps-its-lease");
    final var dispatcher = dispatcherOf(
        connections, oneLaneLeasingFor(A_LEASE_LONG_ENOUGH_TO_WATCH), aRouterWhoseAdapterHoldsTheLane(), table);
    dispatcher.prepareSchema();
    // three entries of one aggregate meet one lane: the first holds it and the other two
    // wait in its queue
    final var entries = entriesOfOneAggregate(connections, table, 3);
    final var waiting = entries.get(2);

    try {
      dispatcher.start();
      assertTrue(
          theFirstCallStarted.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS),
          "the first entry was never dispatched");
      waitUntil(
          "the entry waiting for the lane was never claimed",
          () -> entryOf(connections, table, waiting).leasedBy() != null);
      final var claimedBy = entryOf(connections, table, waiting).leasedBy();
      final var leaseAtTheStart = entryOf(connections, table, waiting).leasedUntil();

      // the moment the lease moves is the whole test. A renewal pushes it along after a
      // third of the lease, while an entry nobody renews keeps it until it has run out and
      // the next poll claims the entry anew - which moves the lease as well and looks the
      // same afterwards
      var lease = leaseAtTheStart;
      while (!lease.isAfter(leaseAtTheStart)) {
        assertTrue(
            Instant.now().isBefore(leaseAtTheStart),
            "the lease of the entry waiting for its lane moved only after it had run out, so nothing "
                + "renewed it and another node was free to claim the entry");
        Thread.sleep(20);
        lease = entryOf(connections, table, waiting).leasedUntil();
      }

      assertEquals(
          claimedBy,
          entryOf(connections, table, waiting).leasedBy(),
          "the entry changed hands while it was waiting for its lane");
    } finally {
      letTheLaneGo.countDown();
      dispatcher.stop();
    }

  }

  @Test
  @DisplayName("Another node leaves an entry alone which waits for the lane of the node holding it")
  public void anotherNodeLeavesAWaitingEntryAlone() throws Exception {

    final var table = "OUTBOX_WAITING_ENTRY_IS_LEFT_ALONE";
    final var connections = databaseNamed("a-waiting-entry-is-left-alone");
    final var holdingNode = dispatcherOf(
        connections, oneLaneLeasingFor(LEASE), aRouterWhoseAdapterHoldsTheLane(), table);
    final var secondNode = dispatcherOf(
        connections, oneLaneLeasingFor(LEASE), aRouterOfASecondNode(), table);
    holdingNode.prepareSchema();
    final var entries = entriesOfOneAggregate(connections, table, 3);
    final var waiting = entries.get(2);

    try {
      holdingNode.start();
      assertTrue(
          theFirstCallStarted.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS),
          "the first entry was never dispatched");
      // an entry of the second node's own, which says when that node has polled: without it
      // the test would have to guess how long to wait before it may assert "nothing taken"
      final var ofTheSecondNode = anEntryDueNow(connections, table, ANOTHER_AGGREGATE, Instant.now());
      secondNode.start();
      waitUntil(
          "the second node never dispatched anything, so it never polled",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE
              .equals(entryOf(connections, table, ofTheSecondNode).status()));
      // the entries of the first node waited for its lane through several leases by now, so
      // a poll which takes them is a poll which reads a lease nobody renewed
      Thread.sleep(3 * LEASE.toMillis());

      assertEquals(
          1,
          callsIntoTheOtherNode.get(),
          "the second node dispatched an entry the first one is holding");
      final var stillWaiting = entryOf(connections, table, waiting);
      assertEquals(
          JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN, stillWaiting.status(), "the waiting entry was dispatched");
      assertEquals(0, stillWaiting.attempts(), "the waiting entry was attempted by somebody");
    } finally {
      letTheLaneGo.countDown();
      secondNode.stop();
      holdingNode.stop();
    }

  }

  @Test
  @DisplayName("A poller waiting for a full lane holds no connection, so the lane can empty it")
  public void aFullLaneDoesNotHoldTheConnectionItsDispatchNeeds() throws Exception {

    final var database = "a-full-lane-holds-no-connection";
    final var table = "OUTBOX_FULL_LANE_HOLDS_NO_CONNECTION";
    // one connection at a time, which is what turns "somebody holds what somebody else
    // needs" into a test which ends: a poller waiting for a full lane while holding the
    // connection waits for the lane to write down a dispatch, and that write waits for the
    // connection. The test reads the table through a connection of its own, so the two
    // waiting for each other is a failure after the deadline and not a run which hangs
    final var connections = databaseNamedWithOneConnection(database);
    final var whatTheTestReadsWith = databaseNamed(database);
    final var properties = oneLaneLeasingFor(LEASE);
    // the default lease, so no renewal competes for the one connection while the entries
    // travel - what this test is about is the poller, not the renewal
    properties.setAttemptFrequency(Duration.ofSeconds(30));
    // the real payload store, housekeeping and all: every step of the cleanup borrows the
    // one connection and gives it back, so the store the application runs is what this
    // test measures
    final var dispatcher = dispatcherOf(connections, properties, aRouterWhoseAdapterReturnsAtOnce(), table);
    dispatcher.prepareSchema();
    final var entries = entriesOfOneAggregate(whatTheTestReadsWith, table, MORE_THAN_ONE_QUEUE_HOLDS);

    try {
      dispatcher.start();
      waitUntil(
          "the entries never all reached the adapter - the poller and its lane are waiting for each other",
          () -> allDispatched(whatTheTestReadsWith, table, entries));
    } finally {
      letTheLaneGo.countDown();
      dispatcher.stop();
    }

  }

}
