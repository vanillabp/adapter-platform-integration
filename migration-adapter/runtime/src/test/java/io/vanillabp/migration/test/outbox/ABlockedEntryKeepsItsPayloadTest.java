package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the housekeeping of the JDBC outbox removes and what it leaves: the retention
 * counts at the entry, so an entry which is blocked keeps its payload however long the
 * repair takes, a dispatched entry takes its payload with it when it goes, and a payload
 * no entry names is removed by age.
 * <p>
 * The rows are written here rather than scheduled, because what is under test is a
 * store which has been standing for longer than the retention. Nothing dispatches during
 * the test: the entries are BLOCKED respectively DONE, and the poller passes over both.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ABlockedEntryKeepsItsPayloadTest {

  private static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.empty())
      .build();

  private static final String OUTBOX_TABLE = "VANILLABP_PHASE_TWO_OUTBOX";

  private static final String PAYLOAD_TABLE = OUTBOX_TABLE + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX;

  /**
   * How long the test waits for the poll which cleans up. The first poll runs when the
   * dispatcher starts, so this is a guard against a machine which leaves the JVM without
   * a turn, not a measurement of speed.
   */
  private static final long UNTIL_THE_HOUSEKEEPING_RAN = 30_000;

  /**
   * Older than the default retention of seven days, so everything written here is old
   * enough to be removed - which makes the entries the only reason a payload stays.
   */
  private static final Instant LONG_BEFORE_THE_RETENTION = Instant.now().minus(Duration.ofDays(10));

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      PAYLOAD_REFERENCE, IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT, DONE_AT) \
      VALUES (?, 'module', 'Process', ?, '42', 'dummy', ?, ?, NULL, ?, ?, ?, 0, ?, ?)"""
      .formatted(OUTBOX_TABLE);

  private static final String AGE_PAYLOAD = "UPDATE %s SET CREATED_AT = ? WHERE REFERENCE = ?"
      .formatted(PAYLOAD_TABLE);

  private static final String COUNT_ENTRY = "SELECT COUNT(*) FROM %s WHERE ID = ?".formatted(OUTBOX_TABLE);

  private final JdbcConnectionAccess connections = () -> DriverManager
      .getConnection("jdbc:h2:mem:blocked-entry-keeps-its-payload;DB_CLOSE_DELAY=-1", "sa", "");

  private static PhaseTwoCall callWith(
      final String content) {

    return PhaseTwoCall
        .of(
            OPERATION, "module", "Process", "42", null, Map.of(),
            content.getBytes(StandardCharsets.UTF_8));

  }

  /**
   * Writes an entry the way a store left it behind before this test began.
   *
   * @param call The call the entry stands for - its arguments name its payload
   * @param status What became of the entry
   * @param doneAt When it was dispatched, <code>null</code> for an entry which was not
   * @return The entry's id
   */
  private String entry(
      final PhaseTwoCall call,
      final String status,
      final Instant doneAt) throws SQLException {

    final var id = UUID.randomUUID().toString();
    try (var connection = connections.acquire(); var statement = connection.prepareStatement(INSERT_ENTRY)) {
      statement.setString(1, id);
      statement.setString(2, call.operation());
      statement.setString(3, PhaseTwoCall.serializeArgs(call.args()));
      statement.setString(4, call.payloadReference());
      // the key of a blocked entry is released the way a dispatched one releases it,
      // which is why both carry their own id here
      statement.setString(5, id);
      statement.setString(6, status);
      statement.setTimestamp(7, Timestamp.from(LONG_BEFORE_THE_RETENTION));
      statement.setTimestamp(8, Timestamp.from(LONG_BEFORE_THE_RETENTION));
      statement.setTimestamp(9, doneAt == null ? null : Timestamp.from(doneAt));
      statement.executeUpdate();
    }
    return id;

  }

  /**
   * Writes the payload of a call and backdates it, so the age is not what decides.
   */
  private void payloadOlderThanTheRetention(
      final JdbcPhaseTwoPayloadStore payloadStore,
      final PhaseTwoCall call) throws SQLException {

    payloadStore.write(call);
    try (var connection = connections.acquire(); var statement = connection.prepareStatement(AGE_PAYLOAD)) {
      statement.setTimestamp(1, Timestamp.from(LONG_BEFORE_THE_RETENTION));
      statement.setString(2, call.payloadReference());
      statement.executeUpdate();
    }

  }

  private long countEntry(
      final String id) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection.prepareStatement(COUNT_ENTRY)) {
      statement.setString(1, id);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }

  }

  /**
   * The outbox with a housekeeping window which is open while this test runs. The default
   * window is an hour of the night, so a test which wants to watch the housekeeping work
   * says when it may.
   *
   * @return The configuration to build the dispatcher with
   */
  private static PhaseTwoOutboxProperties houseKeepingRightNow() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getHousekeeping().setStart(java.time.LocalTime.MIN);
    properties.getHousekeeping().setEnd(java.time.LocalTime.MAX);
    return properties;

  }

  @Test
  @DisplayName("A blocked entry keeps its payload, a dispatched entry takes its own with it, an orphan goes")
  public void theRetentionCountsAtTheEntry() throws Exception {

    final var payloadStore = new JdbcPhaseTwoPayloadStore(
        connections, PAYLOAD_TABLE, JdbcPhaseTwoOutboxStore.entriesNamingTheirPayload(OUTBOX_TABLE));
    final var dispatcher = new JdbcPhaseTwoOutboxDispatcher(
        connections, houseKeepingRightNow(), OUTBOX_TABLE, payloadStore, () -> null, () -> null, "JdbcPhaseTwoOutbox");
    dispatcher.prepareSchema();

    final var blocked = callWith("the state an operator will send once the cause is gone");
    final var dispatched = callWith("the state a dispatch already carried");
    final var orphan = callWith("written by a transaction which never committed");
    payloadOlderThanTheRetention(payloadStore, blocked);
    payloadOlderThanTheRetention(payloadStore, dispatched);
    payloadOlderThanTheRetention(payloadStore, orphan);
    final var blockedEntry = entry(blocked, JdbcPhaseTwoOutboxDispatcher.STATUS_BLOCKED, null);
    final var dispatchedEntry = entry(
        dispatched, JdbcPhaseTwoOutboxDispatcher.STATUS_DONE, LONG_BEFORE_THE_RETENTION);

    try {
      dispatcher.start();
      // the payload sweep is the last thing the housekeeping does, so a gone orphan says
      // that the whole window ran
      final var deadline = System.currentTimeMillis() + UNTIL_THE_HOUSEKEEPING_RAN;
      while (payloadStore.read(orphan.payloadReference()) != null) {
        assertTrue(System.currentTimeMillis() < deadline, "the housekeeping did not remove the orphaned payload");
        Thread.sleep(50);
      }
    } finally {
      dispatcher.stop();
    }

    assertEquals(1, countEntry(blockedEntry), "a blocked entry waits for a person and no retention removes it");
    assertArrayEquals(
        "the state an operator will send once the cause is gone".getBytes(StandardCharsets.UTF_8),
        payloadStore.read(blocked.payloadReference()),
        "the entry is still there, so its payload has to be there as well");

    assertEquals(0, countEntry(dispatchedEntry), "a dispatched entry goes when its retention ran out");
    assertNull(
        payloadStore.read(dispatched.payloadReference()),
        "the entry took its payload with it");

  }

}
