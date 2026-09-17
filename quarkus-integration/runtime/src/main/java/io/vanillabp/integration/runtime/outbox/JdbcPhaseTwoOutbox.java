package io.vanillabp.integration.runtime.outbox;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import io.vanillabp.integration.runtime.processservice.PlatformDefaultStore;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Quarkus (own code - gruelbox
 * does not support JTA): the outbox entry is written into the configured table
 * ({@code vanillabp.outbox.jdbc.table}, default {@link #DEFAULT_TABLE_NAME}) using a
 * JDBC connection of the Agroal data source. Since the
 * connection is acquired within the still-running JTA transaction, it is enlisted
 * automatically - the entry becomes visible if and only if the transaction commits.
 * <p>
 * The entry persists the fields of the {@link PhaseTwoCall} including the operation
 * discriminator and the elected adapter ID; the workflow-aggregate ID is stored in
 * its serialized (String) form only - conversion back to the aggregate's ID type
 * happens in the core's router at dispatch time.
 * <p>
 * <strong>Deduplication spans the entries still waiting for their dispatch</strong>, as
 * the contract of {@link PhaseTwoOutbox} demands. It is the column <code>DEDUP_KEY</code>
 * which is unique, and it carries the idempotency key only while the entry waits: the
 * dispatcher writes the entry's own ID into it when it marks the entry DONE
 * (see {@link JdbcPhaseTwoOutboxDispatcher}), so the key is free from that moment on
 * while <code>IDEMPOTENCY_KEY</code> keeps it readable for support. An entry without a
 * key gets its ID there right away, which is why the column is never null - a
 * database treating two nulls as equal (SQL Server does) would otherwise refuse the
 * second keyless entry. The at-least-once guarantee is unaffected, because a
 * redispatch reads the same entry: it is <code>STATUS</code>, <code>ATTEMPTS</code>
 * and <code>NEXT_ATTEMPT_AT</code> of this table which carry it, never the key.
 * <p>
 * A duplicate is detected by a read before the insert rather than by the constraint
 * violation: on PostgreSQL a failed statement leaves the whole transaction aborted, so
 * the aggregate the caller just persisted would go down with it. The constraint stays
 * the authority for two nodes scheduling at the same moment - there the losing
 * transaction fails, which is acceptable for an operation that was a duplicate anyway.
 * <p>
 * <strong>A younger call may take the waiting entry's place</strong> instead of being
 * discarded against it, where it says so
 * ({@link PhaseTwoCall#replacingWhatIsStillWaiting()}). The row keeps its ID and its
 * key and gets everything the dispatch reads, and the payload of the entry it replaced
 * is removed in the same transaction. What decides is <code>ATTEMPTS</code>: the
 * dispatcher counts the attempt when it claims an entry, so a zero there means no
 * dispatch has read this entry and none is holding its payload. The update carries that
 * condition, which makes it the same optimistic lock the claim is - if a poller wins
 * the row, the update matches nothing and the call becomes an entry of its own, with
 * <code>DEDUP_KEY</code> set to its own ID because the key belongs to the entry on its
 * way.
 * <p>
 * The {@link PhaseTwoCall#args()} map is persisted GENERICALLY in its serialized
 * form ({@link PhaseTwoCall#serializeArgs(java.util.Map)}, column
 * <code>ARGS</code>) - the store stays operation-agnostic (stores never interpret
 * arguments; only the core's router does).
 */
@ApplicationScoped
@Slf4j
public class JdbcPhaseTwoOutbox implements PhaseTwoOutbox, PlatformDefaultStore {

  /**
   * The default name of the table used to store outbox entries (override via
   * <code>vanillabp.outbox.jdbc.table</code> - every outbox instance needs its own
   * table, two dispatchers polling the same table would compete and
   * double-dispatch).
   */
  public static final String DEFAULT_TABLE_NAME = "VANILLABP_PHASE_TWO_OUTBOX";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '%s', ?, 0, ?)""";

  private static final String SELECT_PENDING_ENTRY = """
      SELECT ID, ARGS, ATTEMPTS FROM %s WHERE DEDUP_KEY = ?""";

  /**
   * Puts a younger call into the row of the entry it replaces. Everything the dispatch
   * reads is overwritten, the identifiers included, because an extension is free to
   * derive one key from several calls; what stays is the row's ID, so a poller holding
   * the entry it read a moment ago still addresses the same row.
   * <p>
   * <code>ATTEMPTS = 0</code> is the whole guard: an entry no dispatch has taken yet is
   * one nobody is reading, and a poller which claims it while this update waits for the
   * row finds the update matching no row afterwards.
   */
  private static final String REPLACE_PENDING_ENTRY = """
      UPDATE %s \
      SET OPERATION = ?, AGGREGATE_ID = ?, ADAPTER_ID = ?, ARGS = ?, CREATED_AT = ?, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND ATTEMPTS = 0""";

  /**
   * Resolves the configured name of the payload table
   * (<code>vanillabp.outbox.jdbc.payload-table</code>, falling back to
   * {@link io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore#DEFAULT_TABLE_NAME}).
   *
   * @param properties The outbox configuration
   * @return The table name
   */
  static String payloadTableName(
      final io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties properties) {

    final var table = properties
        .getJdbc()
        .getPayloadTable();
    return table == null
        ? io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore.DEFAULT_TABLE_NAME
        : table;

  }

  /**
   * Resolves the configured table name (<code>vanillabp.outbox.jdbc.table</code>,
   * falling back to {@link #DEFAULT_TABLE_NAME}).
   *
   * @param properties The outbox configuration
   * @return The table name
   */
  static String tableName(
      final io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties properties) {

    final var table = properties
        .getJdbc()
        .getTable();
    return table == null ? DEFAULT_TABLE_NAME : table;

  }

  @Inject
  Instance<DataSource> dataSource;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  @Inject
  JdbcPhaseTwoOutboxDispatcher dispatcher;

  @Override
  public QuarkusPersistenceTechnology.Technology technology() {

    return QuarkusPersistenceTechnology.Technology.JPA;

  }

  /**
   * Whether this default outbox is usable: the extension registers the bean at
   * build time, but without a configured datasource it cannot store anything - an
   * unusable default must not be selected for an aggregate (the startup validation
   * then reports "no outbox available" with the remedies instead of failing at the
   * first workflow start).
   *
   * @return Whether a datasource is available
   */
  @Override
  public boolean isAvailable() {

    return dataSource.isResolvable();

  }

  /**
   * The adapter ids the OPEN entries of one BPMN process are waiting for: an
   * id which is not configured any more means that it was renamed or removed too early,
   * and both leave the workflow of a START entry unstarted.
   */
  @Override
  public java.util.Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (!dataSource.isResolvable()) {
      return java.util.Set.of();
    }
    final var tableName = tableName(dispatcher.getProperties());
    final var selectAdapterIds = """
        SELECT DISTINCT ADAPTER_ID FROM %s \
        WHERE WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND STATUS = ? AND ADAPTER_ID IS NOT NULL"""
        .formatted(tableName);
    try (var connection = dataSource.get().getConnection(); var statement = connection
        .prepareStatement(selectAdapterIds)) {
      statement.setString(1, workflowModuleId);
      statement.setString(2, bpmnProcessId);
      statement.setString(3, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
      try (var resultSet = statement.executeQuery()) {
        final var adapterIds = new java.util.LinkedHashSet<String>();
        while (resultSet.next()) {
          adapterIds.add(resultSet.getString(1));
        }
        return adapterIds;
      }
    } catch (final java.sql.SQLException e) {
      // a startup diagnosis must not keep an application from booting: unanswered is
      // what a store which cannot tell answers anyway
      log
          .debug(
              "Could not read the adapter ids of open outbox entries of BPMN process '{}' "
                  + "(workflow module '{}') from table '{}'",
              bpmnProcessId,
              workflowModuleId,
              tableName,
              e);
      return java.util.Set.of();
    }

  }

  /**
   * Counts the entries waiting for their dispatch. A single indexed count over the
   * outbox table, which holds what did not run yet plus what is kept until the
   * retention passes - the same table the dispatcher polls.
   */
  @Override
  public java.util.OptionalLong pendingCalls() {

    if (!dataSource.isResolvable()) {
      return java.util.OptionalLong.empty();
    }
    final var countPending = "SELECT COUNT(*) FROM %s WHERE STATUS = ?"
        .formatted(tableName(dispatcher.getProperties()));
    try (var connection = dataSource.get().getConnection(); var statement = connection.prepareStatement(countPending)) {
      statement.setString(1, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? java.util.OptionalLong.of(resultSet.getLong(1))
            : java.util.OptionalLong.empty();
      }
    } catch (final SQLException e) {
      // a metric must never be the reason an application fails - the gauge reports
      // nothing for this collection and the next one tries again
      log.debug("Could not count the pending entries of the JDBC phase-two outbox", e);
      return java.util.OptionalLong.empty();
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    if (txRegistry.getTransactionKey() == null) {
      throw new IllegalStateException(
          """
              No transaction active! The phase-two outbox has to be used within the still-running \
              transaction persisting the workflow aggregate.""");
    }
    if (!dataSource.isResolvable()) {
      throw new IllegalStateException(
          """
              No datasource available! The JDBC-based phase-two outbox requires a configured \
              default datasource (quarkus-agroal).""");
    }

    final var now = Instant.now();
    final var tableName = tableName(dispatcher.getProperties());
    final var entryId = UUID.randomUUID().toString();
    final var idempotencyKey = call.idempotencyKey().orElse(null);
    final var insertEntry = INSERT_ENTRY
        .formatted(
            tableName,
            JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
    // what the unique constraint sees. An operation which must not be deduplicated
    // occupies its own ID instead of a null, because not every database treats two
    // nulls as different values, and a second entry beside one a dispatch already took
    // does the same: the key belongs to the entry on its way
    var dedupKey = idempotencyKey == null ? entryId : idempotencyKey;
    try (var connection = dataSource.get().getConnection()) {
      final var waiting = idempotencyKey == null
          ? null
          : pendingEntry(connection, tableName, idempotencyKey);
      if (waiting != null) {
        if (!call.replacesWhatIsStillWaiting()) {
          logDiscardedSchedule(call);
          return false;
        }
        if (replacePendingEntry(connection, tableName, waiting, call, now)) {
          triggerPollAfterCommit();
          return true;
        }
        // a poller claimed the entry between the read and the update: it runs to its
        // end and this call becomes an entry of its own
        logSecondEntryBesideAClaimedOne(call);
        dedupKey = entryId;
      }
      try (var statement = connection.prepareStatement(insertEntry)) {
        statement.setString(1, entryId);
        statement.setString(2, call.workflowModuleId());
        statement.setString(3, call.bpmnProcessId());
        statement.setString(4, call.operation());
        statement.setString(5, call.workflowAggregateId());
        statement.setString(6, call.adapterId());
        statement.setString(7, PhaseTwoCall.serializeArgs(call.args()));
        statement.setString(8, idempotencyKey);
        statement.setString(9, dedupKey);
        statement.setTimestamp(10, Timestamp.from(now));
        statement.setTimestamp(11, Timestamp.from(now));
        statement.executeUpdate();
      }
    } catch (SQLException e) {
      // two nodes scheduling the same operation at the same moment: the read above
      // found nothing on both, and the constraint decided
      if (isDuplicateKey(e)) {
        logDiscardedSchedule(call);
        return false;
      }
      throw new RuntimeException(
          "Could not write the phase-two outbox entry for BPMN process '%s' of workflow module '%s'!"
              .formatted(call.bpmnProcessId(), call.workflowModuleId()), e);
    }

    // the entry is in, so the bytes it names may follow - in this very transaction, and
    // only now, because a schedule discarded as a duplicate must leave nothing behind
    if (call.hasPayload()) {
      dispatcher.getPayloadStore().write(call);
    }

    triggerPollAfterCommit();

    return true;

  }

  /**
   * A call which replaces goes the same way as any other, and this is the one method
   * which says so - the mark travels in the call, so {@link #schedule(PhaseTwoCall)}
   * reads it where the entry is written.
   */
  @Override
  public boolean scheduleReplacingWhatIsStillWaiting(
      final PhaseTwoCall call) {

    return schedule(call.replacingWhatIsStillWaiting());

  }

  /**
   * Dispatches the entry right after the transaction was committed; recovery after a
   * crash is covered by the dispatcher's fixed-delay poller.
   */
  private void triggerPollAfterCommit() {

    txRegistry.registerInterposedSynchronization(new Synchronization() {
      @Override
      public void beforeCompletion() {
        // nothing to do
      }

      @Override
      public void afterCompletion(
          final int status) {
        if (status == Status.STATUS_COMMITTED) {
          dispatcher.triggerPoll();
        }
      }
    });

  }

  /**
   * The entry of this key still waiting for its dispatch, or <code>null</code> where
   * the key is free. Nothing else can carry the key: the dispatcher replaces it by the
   * entry's ID when the entry is marked DONE or blocked.
   */
  private static PendingEntry pendingEntry(
      final java.sql.Connection connection,
      final String tableName,
      final String idempotencyKey) throws SQLException {

    try (var statement = connection.prepareStatement(SELECT_PENDING_ENTRY.formatted(tableName))) {
      statement.setString(1, idempotencyKey);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? new PendingEntry(resultSet.getString(1), resultSet.getString(2), resultSet.getInt(3))
            : null;
      }
    }

  }

  /**
   * Puts the younger call into the row of the waiting entry, its payload included.
   * <p>
   * All three writes ride the transaction the call was scheduled in, so no reader ever
   * sees one of them without the others, and a rollback takes them all. Removing the
   * replaced payload is safe for the reason the replacement itself is safe: an entry
   * which no dispatch has claimed has had its payload read by nobody.
   *
   * @return Whether the entry was replaced - <code>false</code> where a poller claimed
   *         it in the meantime, which makes the call an entry of its own
   */
  private boolean replacePendingEntry(
      final java.sql.Connection connection,
      final String tableName,
      final PendingEntry waiting,
      final PhaseTwoCall call,
      final Instant now) throws SQLException {

    if (waiting.attempts() > 0) {
      return false;
    }
    final boolean replaced;
    try (var statement = connection.prepareStatement(REPLACE_PENDING_ENTRY.formatted(tableName))) {
      statement.setString(1, call.operation());
      statement.setString(2, call.workflowAggregateId());
      statement.setString(3, call.adapterId());
      statement.setString(4, PhaseTwoCall.serializeArgs(call.args()));
      statement.setTimestamp(5, Timestamp.from(now));
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setString(7, waiting.id());
      replaced = statement.executeUpdate() == 1;
    }
    if (!replaced) {
      return false;
    }
    if (call.hasPayload()) {
      dispatcher.getPayloadStore().write(call);
    }
    final var replacedReference = PhaseTwoCall
        .deserializeArgs(waiting.args())
        .get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    if (replacedReference != null) {
      dispatcher.getPayloadStore().remove(replacedReference);
    }
    logReplacedEntry(call);
    return true;

  }

  /**
   * What a store needs to know about the entry a younger call meets: which row it is,
   * which payload it names, and whether a dispatch has taken it already.
   *
   * @param id The entry's own ID
   * @param args The arguments it persisted, holding the reference of its payload
   * @param attempts How often a dispatch claimed it - zero means nobody read it yet
   */
  private record PendingEntry(String id, String args, int attempts) {
  }

  /**
   * A younger call took the place of the entry which was waiting. At DEBUG for the
   * reason a discard is: it is what the caller asked for, and under a backlog it
   * happens as often as reports are planned.
   */
  private static void logReplacedEntry(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' replaced the "
            + "entry which was waiting for its dispatch",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

  /**
   * A younger call could not take the place of the entry it meant to replace, because a
   * dispatch had claimed it. It becomes an entry of its own, so the handler is called
   * twice - worth a line, because an application counting its reports finds the second
   * one here.
   */
  private static void logSecondEntryBesideAClaimedOne(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' asked to "
            + "replace an entry a dispatch had already taken - that one runs to its end and this "
            + "call becomes an entry of its own",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

  /**
   * The technical half of a discarded schedule. Which of the two causes it was - a
   * redelivered dispatch or an operation lost against one still waiting - the store
   * cannot tell, so the core reports it to the caller and this line stays at DEBUG.
   */
  private static void logDiscardedSchedule(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' is still "
            + "waiting for its dispatch - the schedule of an identical operation was discarded",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

  /**
   * Whether the given exception signals a violated unique constraint (= an operation
   * of this key is already planned).
   *
   * @param e The exception raised by the insert
   * @return Whether the insert failed due to a duplicate key
   */
  private static boolean isDuplicateKey(
      final SQLException e) {

    // PostgreSQL's JDBC driver does not map unique violations to the dedicated
    // subclass - fall back to the standard SQL state class 23 (integrity
    // constraint violation) for such drivers
    return (e instanceof SQLIntegrityConstraintViolationException) || ((e.getSQLState() != null) && e.getSQLState()
        .startsWith("23"));

  }

}
