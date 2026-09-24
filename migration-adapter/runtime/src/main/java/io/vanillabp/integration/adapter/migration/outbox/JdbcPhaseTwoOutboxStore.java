package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * The {@link PhaseTwoOutbox} of an application whose workflow aggregates live in a
 * relational database, on every platform VanillaBP supports: the outbox entry is written
 * into the configured table (<code>vanillabp.outbox.jdbc.table</code>, default
 * {@link #DEFAULT_TABLE_NAME}) on a connection which takes part in the transaction the
 * caller is in, so the entry becomes visible if and only if that transaction commits.
 * <p>
 * What differs between the platforms is the transaction and nothing else: Spring Boot
 * binds its connection through {@code DataSourceUtils}, Quarkus enlists an Agroal
 * connection in the running JTA transaction. Both hand that in as a
 * {@link JdbcConnectionAccess} and a {@link PhaseTwoOutboxTransaction}, and the
 * behaviour below is then the same code for both (decision 75 in the repository's
 * DECISIONS.md).
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
 * is removed in the same transaction. What decides is the pair <code>ATTEMPTS</code> and
 * <code>LEASED_UNTIL</code>: no attempt of this entry has ended and nobody is dispatching
 * it right now, so no dispatch has read it and none is holding its payload. The update
 * carries both conditions, which makes it the same optimistic lock the claim is - if a
 * poller wins the row, the update matches nothing and the call becomes an entry of its own, with
 * <code>DEDUP_KEY</code> set to its own ID because the key belongs to the entry on its
 * way.
 * <p>
 * The {@link PhaseTwoCall#args()} map is persisted GENERICALLY in its serialized
 * form ({@link PhaseTwoCall#serializeArgs(java.util.Map)}, column
 * <code>ARGS</code>) - the store stays operation-agnostic (stores never interpret
 * arguments; only the core's router does).
 */
@Slf4j
public class JdbcPhaseTwoOutboxStore implements PhaseTwoOutbox {

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

  private static final String SELECT_ADAPTER_IDS = """
      SELECT DISTINCT ADAPTER_ID FROM %s \
      WHERE WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND STATUS = ? AND ADAPTER_ID IS NOT NULL""";

  private static final String COUNT_PENDING_ENTRIES = "SELECT COUNT(*) FROM %s WHERE STATUS = ?";

  private static final String SELECT_OLDEST_PENDING_ENTRY = "SELECT MIN(CREATED_AT) FROM %s WHERE STATUS = ?";

  /**
   * Puts a younger call into the row of the entry it replaces. Everything the dispatch
   * reads is overwritten, the identifiers included, because an extension is free to
   * derive one key from several calls; what stays is the row's ID, so a poller holding
   * the entry it read a moment ago still addresses the same row.
   * <p>
   * The guard is that no attempt has ended and no lease is running: an entry no dispatch
   * has taken is one nobody is reading, and a poller which claims it while this update waits
   * for the row finds the update matching no row afterwards. Both halves are needed, because
   * the attempts are written when an attempt ends - a dispatch which is on its way right now
   * still shows zero of them and is named by the lease alone.
   */
  private static final String REPLACE_PENDING_ENTRY = """
      UPDATE %s \
      SET OPERATION = ?, AGGREGATE_ID = ?, ADAPTER_ID = ?, ARGS = ?, CREATED_AT = ?, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND ATTEMPTS = 0 AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?)""";

  /**
   * Resolves the name of the payload table: the configured one
   * (<code>vanillabp.outbox.jdbc.payload-table</code>) where there is one, and
   * otherwise the name of the outbox table plus
   * {@link JdbcPhaseTwoPayloadStore#TABLE_NAME_SUFFIX}. An application which renames
   * the outbox to keep two deployments apart gets the payloads renamed with it, so the
   * second deployment does not house-keep the rows of the first.
   *
   * @param properties The outbox configuration
   * @return The table name
   */
  public static String payloadTableName(
      final PhaseTwoOutboxProperties properties) {

    final var table = properties
        .getJdbc()
        .getPayloadTable();
    return table == null
        ? tableName(properties) + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX
        : table;

  }

  /**
   * Resolves the configured table name (<code>vanillabp.outbox.jdbc.table</code>,
   * falling back to {@link #DEFAULT_TABLE_NAME}).
   *
   * @param properties The outbox configuration
   * @return The table name
   */
  public static String tableName(
      final PhaseTwoOutboxProperties properties) {

    final var table = properties
        .getJdbc()
        .getTable();
    return table == null ? DEFAULT_TABLE_NAME : table;

  }

  private final JdbcConnectionAccess connections;

  private final PhaseTwoOutboxTransaction transaction;

  private final String tableName;

  private final JdbcPhaseTwoPayloadStore payloadStore;

  /**
   * What is called after the commit which wrote an entry: the dispatcher's poll, pulled
   * forward to now. Crash recovery does not depend on it - the poller finds the entry
   * anyway - it is what makes the ordinary case immediate.
   */
  private final Runnable dispatchWhatIsDue;

  private final String insertEntry;

  private final String selectPendingEntry;

  private final String replacePendingEntry;

  private final String selectAdapterIds;

  private final String countPendingEntries;

  private final String selectOldestPendingEntry;

  /**
   * Builds the store of one JDBC outbox: the statements for the table it was given, and
   * the collaborators the platform has to hand it. The table is created by the dispatcher
   * of that outbox, not here.
   *
   * @param connections How this platform hands out a connection taking part in the
   *          transaction currently running
   * @param transaction How this platform answers whether a transaction is running and
   *          tells the store that it committed
   * @param tableName The table entries are written into
   * @param payloadStore Where the bytes of a call which carries a payload are written
   * @param dispatchWhatIsDue What to run after the commit, see
   *          {@link #dispatchWhatIsDue}
   */
  public JdbcPhaseTwoOutboxStore(
      final JdbcConnectionAccess connections,
      final PhaseTwoOutboxTransaction transaction,
      final String tableName,
      final JdbcPhaseTwoPayloadStore payloadStore,
      final Runnable dispatchWhatIsDue) {

    this.connections = connections;
    this.transaction = transaction;
    this.tableName = tableName;
    this.payloadStore = payloadStore;
    this.dispatchWhatIsDue = dispatchWhatIsDue;
    this.insertEntry = INSERT_ENTRY.formatted(tableName, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
    this.selectPendingEntry = SELECT_PENDING_ENTRY.formatted(tableName);
    this.replacePendingEntry = REPLACE_PENDING_ENTRY.formatted(tableName);
    this.selectAdapterIds = SELECT_ADAPTER_IDS.formatted(tableName);
    this.countPendingEntries = COUNT_PENDING_ENTRIES.formatted(tableName);
    this.selectOldestPendingEntry = SELECT_OLDEST_PENDING_ENTRY.formatted(tableName);

  }

  /**
   * The table this store was built for. It is asked wherever a message or a housekeeping
   * has to name the table: every outbox instance has one of its own, so a message which
   * named the default would send a reader looking in the wrong place.
   *
   * @return The table this store writes its entries into
   */
  public String getTableName() {

    return tableName;

  }

  /**
   * The adapter ids the OPEN entries of one BPMN process are waiting for: an
   * id which is not configured any more means that it was renamed or removed too early,
   * and both leave the workflow of a START entry unstarted.
   */
  @Override
  public Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(selectAdapterIds)) {
        statement.setString(1, workflowModuleId);
        statement.setString(2, bpmnProcessId);
        statement.setString(3, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
        try (var resultSet = statement.executeQuery()) {
          final var adapterIds = new LinkedHashSet<String>();
          while (resultSet.next()) {
            adapterIds.add(resultSet.getString(1));
          }
          return adapterIds;
        }
      }
    } catch (final SQLException e) {
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
      return Set.of();
    } finally {
      release(connection);
    }

  }

  /**
   * Counts the entries waiting for their dispatch. A single indexed count over the
   * outbox table, which holds what did not run yet plus what is kept until the
   * retention passes - the same table the dispatcher polls.
   */
  @Override
  public OptionalLong pendingCalls() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(countPendingEntries)) {
        statement.setString(1, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
        try (var resultSet = statement.executeQuery()) {
          return resultSet.next()
              ? OptionalLong.of(resultSet.getLong(1))
              : OptionalLong.empty();
        }
      }
    } catch (final SQLException e) {
      // a metric must never be the reason an application fails - the gauge reports
      // nothing for this collection and the next one tries again
      log.debug("Could not count the pending entries of the JDBC phase-two outbox", e);
      return OptionalLong.empty();
    } finally {
      release(connection);
    }

  }

  /**
   * How long the oldest waiting entry has been waiting, read from
   * <code>CREATED_AT</code> - the moment the entry was written, which a replacing call
   * sets anew because the row then carries a younger operation.
   * <p>
   * The index it is read along spans STATUS and CREATED_AT, so the database answers from
   * the first entry of that index instead of walking the waiting ones. Without the index
   * the answer costs what the count of the waiting entries costs, and both grow with the
   * backlog: measured on PostgreSQL 16.15 in September 2026, 0.17 ms with a backlog of
   * 1000 entries, 28 ms with 100000 and 78 ms with 500000, against 0.07 ms at every size
   * with the index.
   */
  @Override
  public Optional<Duration> ageOfOldestPendingCall() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(selectOldestPendingEntry)) {
        statement.setString(1, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
        try (var resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return Optional.empty();
          }
          final var oldest = resultSet.getTimestamp(1);
          // no row at all means nothing waits, and that zero is a measurement: the
          // outbox owes nothing
          return Optional
              .of(oldest == null
                  ? Duration.ZERO
                  : PhaseTwoOutbox.waitedSince(oldest.toInstant()));
        }
      }
    } catch (final SQLException e) {
      // a metric must never be the reason an application fails - the gauge reports
      // nothing for this collection and the next one tries again
      log.debug("Could not read the oldest pending entry of the JDBC phase-two outbox", e);
      return Optional.empty();
    } finally {
      release(connection);
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    if (!transaction.isActive()) {
      throw new IllegalStateException(
          """
              No transaction active! The phase-two outbox has to be used within the still-running \
              transaction persisting the workflow aggregate.""");
    }

    final var now = Instant.now();
    final var entryId = UUID.randomUUID().toString();
    final var idempotencyKey = call.idempotencyKey().orElse(null);
    // what the unique constraint sees. An operation which must not be deduplicated
    // occupies its own ID instead of a null, because not every database treats two
    // nulls as different values, and a second entry beside one a dispatch already took
    // does the same: the key belongs to the entry on its way
    var dedupKey = idempotencyKey == null ? entryId : idempotencyKey;
    Connection connection = null;
    try {
      connection = connections.acquire();
      final var waiting = idempotencyKey == null
          ? null
          : pendingEntry(connection, idempotencyKey);
      if (waiting != null) {
        if (!call.replacesWhatIsStillWaiting()) {
          logDiscardedSchedule(call);
          return false;
        }
        if (replacePendingEntry(connection, waiting, call, now)) {
          dispatchAfterCommit();
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
    } catch (final SQLException e) {
      // two nodes scheduling the same operation at the same moment: the read above
      // found nothing on both, and the constraint decided
      if (isDuplicateKey(e)) {
        logDiscardedSchedule(call);
        return false;
      }
      throw new RuntimeException(
          "Could not write the phase-two outbox entry for BPMN process '%s' of workflow module '%s'!"
              .formatted(call.bpmnProcessId(), call.workflowModuleId()), e);
    } finally {
      release(connection);
    }

    // the entry is in, so the bytes it names may follow - in this very transaction, and
    // only now, because a schedule discarded as a duplicate must leave nothing behind
    if (call.hasPayload()) {
      payloadStore.write(call);
    }

    dispatchAfterCommit();

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
   * crash is covered by the dispatcher's poller.
   */
  private void dispatchAfterCommit() {

    transaction.afterCommit(dispatchWhatIsDue);

  }

  /**
   * The entry of this key still waiting for its dispatch, or <code>null</code> where
   * the key is free. Nothing else can carry the key: the dispatcher replaces it by the
   * entry's ID when the entry is marked DONE or blocked.
   */
  private PendingEntry pendingEntry(
      final Connection connection,
      final String idempotencyKey) throws SQLException {

    try (var statement = connection.prepareStatement(selectPendingEntry)) {
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
      final Connection connection,
      final PendingEntry waiting,
      final PhaseTwoCall call,
      final Instant now) throws SQLException {

    if (waiting.attempts() > 0) {
      return false;
    }
    final boolean replaced;
    try (var statement = connection.prepareStatement(replacePendingEntry)) {
      statement.setString(1, call.operation());
      statement.setString(2, call.workflowAggregateId());
      statement.setString(3, call.adapterId());
      statement.setString(4, PhaseTwoCall.serializeArgs(call.args()));
      statement.setTimestamp(5, Timestamp.from(now));
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setString(7, waiting.id());
      statement.setTimestamp(8, Timestamp.from(now));
      replaced = statement.executeUpdate() == 1;
    }
    if (!replaced) {
      return false;
    }
    if (call.hasPayload()) {
      payloadStore.write(call);
    }
    final var replacedReference = PhaseTwoCall
        .deserializeArgs(waiting.args())
        .get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    if (replacedReference != null) {
      payloadStore.remove(replacedReference);
    }
    logReplacedEntry(call);
    return true;

  }

  /**
   * Returns the connection to wherever it came from. A connection which cannot be
   * returned is logged and nothing more: the work it did is committed or rolled back by
   * the transaction it took part in, and a schedule must not fail over the way back.
   *
   * @param connection The connection acquired before, may be <code>null</code>
   */
  private void release(
      final Connection connection) {

    if (connection == null) {
      return;
    }
    try {
      connections.release(connection);
    } catch (final SQLException e) {
      log.warn("Could not release the connection used for the phase-two outbox table '{}'", tableName, e);
    }

  }

  /**
   * What a store needs to know about the entry a younger call meets: which row it is,
   * which payload it names, and whether an attempt of it has ended already.
   *
   * @param id The entry's own ID
   * @param args The arguments it persisted, holding the reference of its payload
   * @param attempts How many attempts of it have ended - zero plus a free lease means
   *          nobody read it yet
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
