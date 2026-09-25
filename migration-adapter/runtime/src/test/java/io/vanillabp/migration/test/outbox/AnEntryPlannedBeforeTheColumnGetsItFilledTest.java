package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An entry which was planned before the outbox table had a column for the payload
 * reference names its payload among its arguments and nowhere the housekeeping looks. Its
 * payload would be removed as an orphan, and the dispatch which is still to come would
 * find no bytes.
 * <p>
 * So the startup fills the column, and it fills it only for the entries which still wait:
 * a dispatched entry gave its payload back at the dispatch, so nothing asks about it any
 * more - and the dispatched entries are the part of the table which grows.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnEntryPlannedBeforeTheColumnGetsItFilledTest {

  private static final String OUTBOX_TABLE = JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME;

  private static final String PAYLOAD_TABLE = JdbcPhaseTwoPayloadStore.DEFAULT_TABLE_NAME;

  private static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.empty())
      .build();

  /**
   * An entry as an earlier version of VanillaBP wrote it: the reference stands among the
   * arguments and the column is empty, which is what a table carried before the column
   * was filled.
   */
  private static final String AN_ENTRY_OF_AN_EARLIER_VERSION = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ARGS, \
      DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT, DONE_AT) \
      VALUES (?, 'module', 'Process', ?, '42', ?, ?, ?, ?, 0, ?, ?)"""
      .formatted(OUTBOX_TABLE);

  private static final String READ_REFERENCE = "SELECT PAYLOAD_REFERENCE FROM %s WHERE ID = ?".formatted(OUTBOX_TABLE);

  private final JdbcConnectionAccess connections = () -> DriverManager
      .getConnection("jdbc:h2:mem:entry-before-the-column;DB_CLOSE_DELAY=-1", "sa", "");

  private static PhaseTwoCall callWith(
      final String content) {

    return PhaseTwoCall
        .of(
            OPERATION, "module", "Process", "42", null, Map.of(),
            content.getBytes(StandardCharsets.UTF_8));

  }

  private JdbcPhaseTwoOutboxDispatcher aDispatcher() {

    return new JdbcPhaseTwoOutboxDispatcher(
        connections, new PhaseTwoOutboxProperties(), OUTBOX_TABLE, new JdbcPhaseTwoPayloadStore(
            connections, PAYLOAD_TABLE, JdbcPhaseTwoOutboxStore.entriesNamingTheirPayload(
                OUTBOX_TABLE)), () -> null, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");

  }

  private String anEntryOfAnEarlierVersion(
      final PhaseTwoCall call,
      final String status,
      final Instant doneAt) throws SQLException {

    final var id = UUID
        .randomUUID()
        .toString();
    final var when = Timestamp.from(Instant.now().minus(Duration.ofDays(30)));
    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(AN_ENTRY_OF_AN_EARLIER_VERSION)) {
      statement.setString(1, id);
      statement.setString(2, call.operation());
      statement.setString(3, PhaseTwoCall.serializeArgs(call.args()));
      statement.setString(4, id);
      statement.setString(5, status);
      statement.setTimestamp(6, when);
      statement.setTimestamp(7, when);
      statement.setTimestamp(8, doneAt == null ? null : Timestamp.from(doneAt));
      statement.executeUpdate();
    }
    return id;

  }

  private String referenceOf(
      final String entryId) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection.prepareStatement(READ_REFERENCE)) {
      statement.setString(1, entryId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }

  }

  @Test
  @DisplayName("The startup fills the column of the entries which still wait, and leaves the history alone")
  public void theStartupFillsWhatStillWaits() throws Exception {

    final var dispatcher = aDispatcher();
    dispatcher.prepareSchema();
    final var waiting = callWith("the state an entry is still waiting to send");
    final var blocked = callWith("the state an operator will send once the cause is gone");
    final var dispatched = callWith("the state a dispatch already carried");
    final var waitingEntry = anEntryOfAnEarlierVersion(waiting, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN, null);
    final var blockedEntry = anEntryOfAnEarlierVersion(blocked, JdbcPhaseTwoOutboxDispatcher.STATUS_BLOCKED, null);
    final var dispatchedEntry = anEntryOfAnEarlierVersion(
        dispatched, JdbcPhaseTwoOutboxDispatcher.STATUS_DONE, Instant.now().minus(Duration.ofDays(30)));
    assertNull(referenceOf(waitingEntry), "the entry was written the way an earlier version wrote it");

    // the next start of the application, with the column in place
    aDispatcher().prepareSchema();

    assertEquals(
        waiting.payloadReference(),
        referenceOf(waitingEntry),
        "an entry which still waits has to name its payload where the housekeeping asks");
    assertEquals(
        blocked.payloadReference(),
        referenceOf(blockedEntry),
        "and so does an entry somebody is going to repair");
    assertNull(
        referenceOf(dispatchedEntry),
        "a dispatched entry gave its payload back at the dispatch, so the history is not touched");

  }

  @Test
  @DisplayName("An entry which carries no payload is left as it is")
  public void anEntryWithoutAPayloadIsLeftAlone() throws Exception {

    final var dispatcher = aDispatcher();
    dispatcher.prepareSchema();
    final var withoutAPayload = PhaseTwoCall
        .of(PhaseOperation.START_WORKFLOW, "module", "Process", "42", "dummy", Map.of());
    final var entry = anEntryOfAnEarlierVersion(withoutAPayload, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN, null);

    aDispatcher().prepareSchema();

    assertNull(referenceOf(entry));
    assertNotNull(entry);

  }

}
