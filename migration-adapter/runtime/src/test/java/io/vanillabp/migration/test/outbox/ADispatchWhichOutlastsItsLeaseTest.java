package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What the lease of the JDBC outbox is for. A dispatch may take longer than the time the
 * claim leased the entry for. It then keeps its entry, and nobody carries it out a second
 * time.
 * <p>
 * This was measured before the lease existed, with the numbers below. One operation had six
 * counted attempts and six calls into the adapter. Five of the calls came after the entry
 * was already marked DONE. The late calls read a payload which had been removed, and wrote
 * DONE_AT anew. One of them could have written BLOCKED over a DONE. The claim leased the
 * entry for one <code>attempt-frequency</code>, and the next poll simply took it back.
 * <p>
 * The three tests assert this. One operation is delivered once. <code>ATTEMPTS</code> counts
 * what was attempted, not what was claimed. And an entry whose holder died goes to whoever
 * polls next.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class ADispatchWhichOutlastsItsLeaseTest {

  /**
   * How long a claim lasts here. It is so short that every poll during the dispatch below
   * would have taken the entry back. That made the defect visible in the first place.
   */
  private static final Duration LEASE = Duration.ofMillis(500);

  /**
   * How long one dispatch takes here. It is six times the lease, like a handler which calls
   * a system of somebody else.
   */
  private static final Duration A_DISPATCH_WHICH_TAKES_ITS_TIME = Duration.ofSeconds(3);

  /**
   * How long a test waits for something it expects to happen. It guards against a machine
   * which leaves the JVM without a turn. It does not measure speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT, LEASED_BY, LEASED_UNTIL) \
      VALUES (?, '%s', '%s', ?, '%s', 'dummy', NULL, NULL, ?, '%s', ?, 0, ?, ?, ?)""";

  private static final String SELECT_ENTRY = "SELECT STATUS, ATTEMPTS FROM %s WHERE ID = ?";

  @Mock
  private MigrationProcessService<Object> processService;

  /**
   * How often the adapter was called. The whole story is about this number.
   */
  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  /**
   * The first call into the adapter counts it down. So a test can wait until the dispatch
   * has STARTED, instead of sleeping for a while.
   */
  private final CountDownLatch theFirstCallStarted = new CountDownLatch(1);

  /**
   * A database per test. Every test of this class writes into the outbox table and reads
   * what is in it afterwards.
   */
  private JdbcConnectionAccess databaseNamed(
      final String name) {

    return () -> DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * The properties of a store with a short lease. The poll which used to take back the
   * entry of a running dispatch then happens several times per dispatch.
   */
  private static PhaseTwoOutboxProperties leasingFor(
      final Duration lease) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setAttemptFrequency(lease);
    // the poll interval caps the sleep. A short one makes the poll happen again while the
    // dispatch below is still running
    properties.setPollInterval(lease);
    return properties;

  }

  /**
   * A router whose only process service is a double. The double takes its time and counts
   * how often it was called.
   *
   * @param dispatchTakes How long one call into the adapter lasts
   */
  private PhaseTwoRouter aRouterWhoseAdapterTakes(
      final Duration dispatchTakes) {

    when(processService.getWorkflowModuleId()).thenReturn(MODULE);
    when(processService.getBpmnProcessId()).thenReturn(PROCESS);
    when(processService.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          callsIntoTheAdapter.incrementAndGet();
          theFirstCallStarted.countDown();
          Thread.sleep(dispatchTakes.toMillis());
          return null;
        })
        .when(processService)
        .executePhaseTwo(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(processService);
    return router;

  }

  private JdbcPhaseTwoOutboxDispatcher dispatcherOf(
      final JdbcConnectionAccess connections,
      final PhaseTwoOutboxProperties properties,
      final PhaseTwoRouter router,
      final String table) {

    return new JdbcPhaseTwoOutboxDispatcher(
        connections, properties, table, new JdbcPhaseTwoPayloadStore(connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX, JdbcPhaseTwoOutboxStore
            .entriesNamingTheirPayload(table)), () -> router, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");

  }

  /**
   * Writes an entry which is due now.
   *
   * @param connections The database to write into
   * @param table The outbox table
   * @param leasedBy Who holds the entry, <code>null</code> for an entry nobody holds
   * @param leasedUntil How long that hold lasts, <code>null</code> with the above
   * @return The entry's id
   */
  private String anEntryDueNow(
      final JdbcConnectionAccess connections,
      final String table,
      final String leasedBy,
      final Instant leasedUntil) throws SQLException {

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
      statement.setString(6, leasedBy);
      statement.setTimestamp(7, leasedUntil == null ? null : Timestamp.from(leasedUntil));
      statement.executeUpdate();
    }
    return id;

  }

  /**
   * @return The status and the number of attempts the row shows now
   */
  private Entry entryOf(
      final JdbcConnectionAccess connections,
      final String table,
      final String id) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(SELECT_ENTRY.formatted(table))) {
      statement.setString(1, id);
      try (var resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "the entry is gone");
        return new Entry(resultSet.getString(1), resultSet.getInt(2));
      }
    }

  }

  private record Entry(String status, int attempts) {
  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final java.util.concurrent.Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A dispatch longer than its lease reaches the adapter once, not once per poll")
  public void aSlowDispatchIsDeliveredOnce() throws Exception {

    final var table = "OUTBOX_SLOW_DISPATCH";
    final var connections = databaseNamed("dispatch-outlasts-its-lease");
    final var dispatcher = dispatcherOf(
        connections, leasingFor(LEASE), aRouterWhoseAdapterTakes(A_DISPATCH_WHICH_TAKES_ITS_TIME), table);
    dispatcher.prepareSchema();
    final var entry = anEntryDueNow(connections, table, null, null);

    try {
      dispatcher.start();
      assertTrue(
          theFirstCallStarted.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS),
          "the entry was never dispatched");
      // the dispatch runs for six leases. Every poll in that window used to take the entry
      // back. The entry is DONE when the one dispatch finished
      waitUntil(
          "the dispatch never finished",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(entryOf(connections, table, entry).status()));
      // long enough for two more polls after the entry was marked. A late call would land
      // here
      Thread.sleep(2 * LEASE.toMillis());
    } finally {
      dispatcher.stop();
    }

    assertEquals(1, callsIntoTheAdapter.get(), "the operation reached the adapter more than once");
    assertEquals(
        1,
        entryOf(connections, table, entry).attempts(),
        "one attempt was made, so one attempt is what the entry counts");

  }

  @Test
  @DisplayName("ATTEMPTS counts an attempt which ended, not a claim which was taken")
  public void attemptsCountWhatWasAttempted() throws Exception {

    final var table = "OUTBOX_ATTEMPTS_COUNT_ATTEMPTS";
    final var connections = databaseNamed("attempts-count-attempts");
    final var dispatcher = dispatcherOf(
        connections, leasingFor(LEASE), aRouterWhoseAdapterTakes(A_DISPATCH_WHICH_TAKES_ITS_TIME), table);
    dispatcher.prepareSchema();
    final var entry = anEntryDueNow(connections, table, null, null);

    try {
      dispatcher.start();
      assertTrue(
          theFirstCallStarted.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS),
          "the entry was never dispatched");
      // the attempt is under way and has not ended yet, so there is nothing to count.
      // 'block-after-attempts' reads this number, and being slow must not use it up
      assertEquals(
          0,
          entryOf(connections, table, entry).attempts(),
          "the claim counted an attempt although no attempt has ended");
      waitUntil(
          "the dispatch never finished",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(entryOf(connections, table, entry).status()));
    } finally {
      dispatcher.stop();
    }

    assertEquals(1, entryOf(connections, table, entry).attempts(), "the attempt which ended was not counted");

  }

  @Test
  @DisplayName("A table without the lease columns ends the boot and names the statement which repairs it")
  public void aTableWithoutTheLeaseColumnsEndsTheBoot() throws Exception {

    final var table = "OUTBOX_WITHOUT_THE_LEASE";
    final var connections = databaseNamed("a-table-without-the-lease");
    // the table an earlier version of VanillaBP created. It has everything but the two
    // columns a claim writes. Every poll would fail on them, so the boot has to say so
    try (var connection = connections.acquire(); var statement = connection.createStatement()) {
      statement
          .executeUpdate(
              """
                  CREATE TABLE %s (\
                  ID VARCHAR(36) PRIMARY KEY, \
                  WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
                  BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
                  OPERATION VARCHAR(255) NOT NULL, \
                  AGGREGATE_ID VARCHAR(1024), \
                  ADAPTER_ID VARCHAR(255), \
                  ARGS VARCHAR(2048), \
                  IDEMPOTENCY_KEY VARCHAR(512), \
                  DEDUP_KEY VARCHAR(512) NOT NULL UNIQUE, \
                  STATUS VARCHAR(16) NOT NULL, \
                  CREATED_AT TIMESTAMP NOT NULL, \
                  ATTEMPTS INT NOT NULL, \
                  NEXT_ATTEMPT_AT TIMESTAMP NOT NULL, \
                  DONE_AT TIMESTAMP)""".formatted(table));
    }
    final var dispatcher = dispatcherOf(connections, leasingFor(LEASE), new PhaseTwoRouter(), table);

    final var boot = assertThrows(IllegalStateException.class, dispatcher::prepareSchema);

    assertTrue(boot.getMessage().contains("LEASED_BY"), boot.getMessage());
    assertTrue(
        boot.getMessage().contains("ALTER TABLE %s ADD LEASED_BY".formatted(table)),
        "the message has to carry the statement which repairs the table: "
            + boot.getMessage());
    assertTrue(
        boot.getMessage().contains("io.vanillabp:vanillabp-schema"),
        "the message has to name where the schema comes from: "
            + boot.getMessage());

  }

  @Test
  @DisplayName("An entry whose holder died is taken over, one whose lease still runs is left alone")
  public void aLeaseWhichRanOutIsTakenOver() throws Exception {

    final var table = "OUTBOX_LEASE_RAN_OUT";
    final var connections = databaseNamed("a-lease-which-ran-out");
    final var dispatcher = dispatcherOf(
        connections, leasingFor(LEASE), aRouterWhoseAdapterTakes(Duration.ZERO), table);
    dispatcher.prepareSchema();
    // both are due, and only the lease tells them apart
    final var leftBehind = anEntryDueNow(
        connections, table, "a-node-which-died", Instant.now().minus(Duration.ofMinutes(1)));
    final var heldBySomebodyElse = anEntryDueNow(
        connections, table, "a-node-which-is-working", Instant.now().plus(Duration.ofHours(1)));

    try {
      dispatcher.start();
      waitUntil(
          "the entry of the node which died was never taken over",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE
              .equals(entryOf(connections, table, leftBehind).status()));
      // two more polls, so the lease decides "not dispatched", not the timing
      Thread.sleep(3 * LEASE.toMillis());
    } finally {
      dispatcher.stop();
    }

    assertEquals(1, callsIntoTheAdapter.get(), "an entry whose lease is still running was dispatched as well");
    final var untouched = entryOf(connections, table, heldBySomebodyElse);
    assertEquals(JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN, untouched.status(), "the leased entry was dispatched");
    assertEquals(0, untouched.attempts(), "the leased entry was attempted");

  }

}
