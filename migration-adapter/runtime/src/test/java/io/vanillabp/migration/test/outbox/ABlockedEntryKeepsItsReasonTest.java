package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
import io.vanillabp.integration.adapter.migration.outbox.LastFailure;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * A blocked entry of the JDBC outbox names its reason in the table, so whoever finds it does
 * not have to look for the line in a log which may be rotated by then. The column is
 * <code>LAST_FAILURE</code>, and every write which ends an attempt sets it.
 * <p>
 * The cases are the ways an entry gets blocked - a permanent failure, the last of the
 * allowed attempts, a wait for the BPMS which ran out - plus the way back: an entry opened
 * again with the statement of the wiki page keeps its reason until the next attempt, and an
 * attempt which goes through empties it.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class ABlockedEntryKeepsItsReasonTest {

  /**
   * How long a test waits for something it expects to happen. It is a guard against a machine
   * which leaves the JVM without a turn, not a measurement of speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  /**
   * The distance between attempts and the length of a claim. Short, so the attempts of a test
   * follow each other quickly.
   */
  private static final Duration SHORT = Duration.ofMillis(200);

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "939";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, '%s', '%s', ?, '%s', 'dummy', NULL, NULL, ?, '%s', ?, 0, ?)""";

  private static final String SELECT_ENTRY = "SELECT STATUS, ATTEMPTS, %s FROM %s WHERE ID = ?";

  /**
   * The statement the wiki page "Blocked outbox entries" gives an operator to open a blocked
   * entry again, word for word apart from the table.
   */
  private static final String OPEN_AGAIN = """
      UPDATE %s \
      SET STATUS = 'OPEN', ATTEMPTS = 0, LEASED_BY = NULL, LEASED_UNTIL = NULL, \
      NEXT_ATTEMPT_AT = CURRENT_TIMESTAMP \
      WHERE ID = ? AND STATUS = 'BLOCKED'""";

  @Mock
  private MigrationProcessService<Object> processService;

  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  private JdbcConnectionAccess databaseNamed(
      final String name) {

    return () -> DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  private static PhaseTwoOutboxProperties allowingTwoAttempts() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setBlockAfterAttempts(2);
    properties.setAttemptFrequency(SHORT);
    properties.setPollInterval(SHORT);
    properties.setWaitForVisibilityAtMost(Duration.ofHours(1));
    return properties;

  }

  /**
   * A router whose process service answers each dispatch the way the test says.
   *
   * @param answer What the adapter does on the dispatch with the given number, counted from
   *          one; it throws to fail the dispatch
   */
  private PhaseTwoRouter aRouterAnswering(
      final Answer answer) {

    when(processService.getWorkflowModuleId()).thenReturn(MODULE);
    when(processService.getBpmnProcessId()).thenReturn(PROCESS);
    when(processService.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          answer.dispatch(callsIntoTheAdapter.incrementAndGet());
          return null;
        })
        .when(processService)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(processService);
    return router;

  }

  @FunctionalInterface
  private interface Answer {

    void dispatch(
        int call) throws Exception;

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

  private String anEntryWrittenAt(
      final JdbcConnectionAccess connections,
      final String table,
      final Instant writtenAt) throws SQLException {

    final var id = UUID.randomUUID().toString();
    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(
            INSERT_ENTRY
                .formatted(table, MODULE, PROCESS, AGGREGATE, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN))) {
      statement.setString(1, id);
      statement.setString(2, PhaseOperation.CORRELATE_MESSAGE.name());
      statement.setString(3, id);
      statement.setTimestamp(4, Timestamp.from(writtenAt));
      statement.setTimestamp(5, Timestamp.from(Instant.now()));
      statement.executeUpdate();
    }
    return id;

  }

  private Entry entryOf(
      final JdbcConnectionAccess connections,
      final String table,
      final String id) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(SELECT_ENTRY.formatted(JdbcPhaseTwoOutboxDispatcher.LAST_FAILURE_COLUMN, table))) {
      statement.setString(1, id);
      try (var resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "the entry is gone");
        return new Entry(resultSet.getString(1), resultSet.getInt(2), resultSet.getString(3));
      }
    }

  }

  private record Entry(String status, int attempts, String lastFailure) {

    boolean isBlocked() {

      return JdbcPhaseTwoOutboxDispatcher.STATUS_BLOCKED.equals(status);

    }

  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(20);
    }

  }

  /**
   * Runs the dispatcher until the entry is blocked.
   *
   * @return The entry as the table holds it then
   */
  private Entry blockedEntry(
      final JdbcConnectionAccess connections,
      final JdbcPhaseTwoOutboxDispatcher dispatcher,
      final String table,
      final String id) throws Exception {

    try {
      dispatcher.start();
      waitUntil("the entry was not blocked", () -> entryOf(connections, table, id).isBlocked());
    } finally {
      dispatcher.stop();
    }
    return entryOf(connections, table, id);

  }

  @Test
  @DisplayName("An entry blocked by a permanent failure names class and message of the failure")
  public void aPermanentFailureIsTheReason() throws Exception {

    final var table = "OUTBOX_REASON_PERMANENT";
    final var connections = databaseNamed("reason-permanent");
    final var dispatcher = dispatcherOf(connections, allowingTwoAttempts(), aRouterAnswering(call -> {
      throw new IllegalStateException(
          "dispatch failed", new PhaseTwoPermanentFailure("the message 'Order paid' is not modelled", null));
    }), table);
    dispatcher.prepareSchema();
    final var id = anEntryWrittenAt(connections, table, Instant.now());

    final var entry = blockedEntry(connections, dispatcher, table, id);

    assertEquals(1, entry.attempts(), "a permanent failure blocks at the first attempt");
    assertEquals(
        "java.lang.IllegalStateException: dispatch failed; caused by "
            + "io.vanillabp.integration.spi.PhaseTwoPermanentFailure: the message 'Order paid' is not modelled",
        entry.lastFailure(),
        "the reason is the failure and its cause, class and message each, in one line");

  }

  @Test
  @DisplayName("Every failed attempt writes its reason, and the last one is what the blocked entry keeps")
  public void theLastOfTheAttemptsIsTheReason() throws Exception {

    final var table = "OUTBOX_REASON_ATTEMPTS";
    final var connections = databaseNamed("reason-attempts");
    final var id = new String[1];
    // what the entry said while its second attempt ran, which is what an operator reads on an
    // entry that is still being repeated
    final List<String> reasonBeforeTheSecondAttempt = new CopyOnWriteArrayList<>();
    final var dispatcher = dispatcherOf(connections, allowingTwoAttempts(), aRouterAnswering(call -> {
      if (call == 2) {
        reasonBeforeTheSecondAttempt.add(entryOf(connections, table, id[0]).lastFailure());
      }
      throw new IllegalStateException("the BPMS refused attempt "
          + call);
    }), table);
    dispatcher.prepareSchema();
    id[0] = anEntryWrittenAt(connections, table, Instant.now());

    final var entry = blockedEntry(connections, dispatcher, table, id[0]);

    assertEquals(
        List.of("java.lang.IllegalStateException: the BPMS refused attempt 1"),
        reasonBeforeTheSecondAttempt,
        "an entry which is still being repeated says why its first attempt failed");
    assertEquals(2, entry.attempts(), "the entry is blocked after the two attempts it was allowed");
    assertEquals(
        "java.lang.IllegalStateException: the BPMS refused attempt 2",
        entry.lastFailure(),
        "the blocked entry names the failure of its last attempt");

  }

  @Test
  @DisplayName("An entry opened again keeps its reason until an attempt goes through and empties it")
  public void openingAgainKeepsTheReasonUntilTheNextAttempt() throws Exception {

    final var table = "OUTBOX_REASON_OPENED_AGAIN";
    final var connections = databaseNamed("reason-opened-again");
    final var blocking = dispatcherOf(connections, allowingTwoAttempts(), aRouterAnswering(call -> {
      throw new PhaseTwoPermanentFailure("the message 'Order paid' is not modelled", null);
    }), table);
    blocking.prepareSchema();
    final var id = anEntryWrittenAt(connections, table, Instant.now());
    final var reason = blockedEntry(connections, blocking, table, id).lastFailure();

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(OPEN_AGAIN.formatted(table))) {
      statement.setString(1, id);
      assertEquals(1, statement.executeUpdate(), "the statement of the wiki page did not open the entry");
    }
    assertEquals(
        reason,
        entryOf(connections, table, id).lastFailure(),
        "opening the entry again does not touch the reason - the next attempt does");

    Mockito.reset(processService);
    final var succeeding = dispatcherOf(connections, allowingTwoAttempts(), aRouterAnswering(call -> {
    }), table);
    try {
      succeeding.start();
      waitUntil(
          "the entry opened again was not dispatched",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(entryOf(connections, table, id).status()));
    } finally {
      succeeding.stop();
    }
    assertNull(
        entryOf(connections, table, id).lastFailure(),
        "the attempt which went through emptied the reason");

  }

  @Test
  @DisplayName("An entry which waited too long for its BPMS says so, with the last answer")
  public void aWaitWhichRanOutIsTheReason() throws Exception {

    final var table = "OUTBOX_REASON_WAITED";
    final var connections = databaseNamed("reason-waited");
    final var dispatcher = dispatcherOf(connections, allowingTwoAttempts(), aRouterAnswering(call -> {
      throw new PhaseTwoRetryLater("the BPMS does not report the workflow yet", SHORT);
    }), table);
    dispatcher.prepareSchema();
    final var id = anEntryWrittenAt(connections, table, Instant.now().minus(Duration.ofHours(2)));

    final var entry = blockedEntry(connections, dispatcher, table, id);

    assertTrue(
        entry.lastFailure().startsWith("Waited PT2H"),
        "the reason does not start with how long the entry waited: "
            + entry.lastFailure());
    assertTrue(
        entry.lastFailure().contains(PhaseTwoOutboxProperties.WAIT_FOR_VISIBILITY_AT_MOST_PROPERTY),
        "the reason does not name the property which ended the wait: "
            + entry.lastFailure());
    assertTrue(
        entry.lastFailure().endsWith("the BPMS does not report the workflow yet"),
        "the reason does not end with the last answer of the adapter: "
            + entry.lastFailure());

  }

  @Test
  @DisplayName("A long reason with characters of several bytes fits the column")
  public void aLongReasonIsCutToTheColumn() throws Exception {

    final var table = "OUTBOX_REASON_LONG";
    final var connections = databaseNamed("reason-long");
    final var dispatcher = dispatcherOf(connections, allowingTwoAttempts(), aRouterAnswering(call -> {
      throw new PhaseTwoPermanentFailure("Prüfung fehlgeschlagen ".repeat(100), null);
    }), table);
    dispatcher.prepareSchema();
    final var id = anEntryWrittenAt(connections, table, Instant.now());

    final var entry = blockedEntry(connections, dispatcher, table, id);

    assertTrue(
        entry.lastFailure().getBytes(StandardCharsets.UTF_8).length <= LastFailure.MAX_BYTES,
        "the reason is longer than the column allows");
    assertTrue(entry.lastFailure().endsWith("..."), "the cut is not marked");

  }

}
