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
 * relational database, on every platform VanillaBP supports. The outbox entry is written
 * into the configured table (<code>vanillabp.outbox.jdbc.table</code>, default
 * {@link #DEFAULT_TABLE_NAME}). The connection takes part in the caller's transaction. So
 * the entry becomes visible if and only if that transaction commits.
 * <p>
 * Only the transaction differs between the platforms. Spring Boot binds its connection
 * through {@code DataSourceUtils}. Quarkus enlists an Agroal connection in the running JTA
 * transaction. Both hand that in as a {@link JdbcConnectionAccess} and a
 * {@link PhaseTwoOutboxTransaction}. Everything below is then the same code for both
 * (decision 75 in the repository's DECISIONS.md).
 * <p>
 * The entry stores the fields of the {@link PhaseTwoCall}, including the name of the
 * operation and the elected adapter ID. The workflow-aggregate ID is stored only in its
 * serialized form, as a String. The core's router converts it back to the aggregate's ID
 * type at dispatch time.
 * <p>
 * <strong>Deduplication spans the entries which still wait for their dispatch</strong>, as
 * the contract of {@link PhaseTwoOutbox} demands. The column <code>DEDUP_KEY</code> is the
 * unique one. It holds the idempotency key only while the entry waits. When the dispatcher
 * marks the entry DONE, it writes the entry's own ID into it (see
 * {@link JdbcPhaseTwoOutboxDispatcher}). So from that moment on the key is free, while
 * <code>IDEMPOTENCY_KEY</code> keeps it readable for support. An entry without a key gets
 * its ID there right away. That is why the column is never null. A database which treats
 * two nulls as equal (SQL Server does) would otherwise refuse the second entry without a
 * key. The at-least-once guarantee does not change, because a repeated dispatch reads the
 * same entry. <code>STATUS</code>, <code>ATTEMPTS</code> and <code>NEXT_ATTEMPT_AT</code>
 * of this table carry that guarantee, never the key.
 * <p>
 * A duplicate is found by a read before the insert, not by the constraint violation. On
 * PostgreSQL a failed statement leaves the whole transaction aborted. So the aggregate
 * which the caller just persisted would be lost with it. The constraint still decides when
 * two nodes schedule at the same moment. There the losing transaction fails, and that is
 * acceptable for an operation which was a duplicate anyway.
 * <p>
 * <strong>A younger call may take the place of the waiting entry</strong>, instead of being
 * discarded against it, where the call says so
 * ({@link PhaseTwoCall#replacingWhatIsStillWaiting()}). The row keeps its ID and its key,
 * and gets everything the dispatch reads. The payload of the replaced entry is removed in
 * the same transaction. The pair <code>ATTEMPTS</code> and <code>LEASED_UNTIL</code>
 * decides. No attempt of this entry has ended, and nobody dispatches it right now. So no
 * dispatch has read it, and none holds its payload. The update has both conditions. That
 * makes it the same optimistic lock as the claim. If a poller wins the row, the update
 * matches nothing, and the call becomes an entry of its own. Its <code>DEDUP_KEY</code> is
 * then its own ID, because the key belongs to the entry which is on its way.
 * <p>
 * The {@link PhaseTwoCall#args()} map is stored GENERICALLY in its serialized form
 * ({@link PhaseTwoCall#serializeArgs(java.util.Map)}, column <code>ARGS</code>). So the
 * store does not depend on any operation. Stores never interpret arguments, only the core's
 * router does.
 */
@Slf4j
public class JdbcPhaseTwoOutboxStore implements PhaseTwoOutbox {

  /**
   * The default name of the table for the outbox entries. It can be changed with
   * <code>vanillabp.outbox.jdbc.table</code>. Every outbox instance needs its own table. Two
   * dispatchers which poll the same table would compete and dispatch twice.
   */
  public static final String DEFAULT_TABLE_NAME = "VANILLABP_PHASE_TWO_OUTBOX";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      PAYLOAD_REFERENCE, IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '%s', ?, 0, ?)""";

  private static final String SELECT_PENDING_ENTRY = """
      SELECT ID, ARGS, ATTEMPTS FROM %s WHERE DEDUP_KEY = ?""";

  private static final String SELECT_ADAPTER_IDS = """
      SELECT DISTINCT ADAPTER_ID FROM %s \
      WHERE WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND STATUS = ? AND ADAPTER_ID IS NOT NULL""";

  private static final String COUNT_PENDING_ENTRIES = "SELECT COUNT(*) FROM %s WHERE STATUS = ?";

  private static final String SELECT_OLDEST_PENDING_ENTRY = "SELECT MIN(CREATED_AT) FROM %s WHERE STATUS = ?";

  /**
   * Puts a younger call into the row of the entry which it replaces. Everything the dispatch
   * reads is overwritten, the identifiers included, because an extension is free to derive
   * one key from several calls. The row's ID stays. So a poller which holds the entry it
   * read a moment ago still addresses the same row.
   * <p>
   * The guard is that no attempt has ended and no lease is running. An entry which no
   * dispatch has taken is one which nobody reads. A poller may claim it while this update
   * waits for the row. Then the update afterwards matches no row. Both halves are needed,
   * because the attempts are written when an attempt ends. A dispatch which is on its way
   * right now still shows zero attempts, and only the lease names it.
   */
  private static final String REPLACE_PENDING_ENTRY = """
      UPDATE %s \
      SET OPERATION = ?, AGGREGATE_ID = ?, ADAPTER_ID = ?, ARGS = ?, PAYLOAD_REFERENCE = ?, \
      CREATED_AT = ?, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND ATTEMPTS = 0 AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?)""";

  /**
   * Resolves the name of the payload table. That is the configured one
   * (<code>vanillabp.outbox.jdbc.payload-table</code>) where there is one. Otherwise it is
   * the name of the outbox table plus {@link JdbcPhaseTwoPayloadStore#TABLE_NAME_SUFFIX}. An
   * application may rename the outbox to keep two deployments apart. Then the payloads are
   * renamed with it, so the second deployment does not clean up the rows of the first.
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
   * Where the entries of this store say which payload they have. The payload store cleans
   * up along that.
   *
   * @param properties The outbox configuration
   * @return The description to build the payload store with
   */
  public static JdbcPhaseTwoPayloadStore.EntriesNamingTheirPayload entriesNamingTheirPayload(
      final PhaseTwoOutboxProperties properties) {

    return entriesNamingTheirPayload(tableName(properties));

  }

  /**
   * Where the entries of one table of this shape say which payload they have: in a column of
   * their own, which an index can reach. The reference travels among the serialized
   * arguments as well (decision 62 in the repository's DECISIONS.md). The column is written
   * from the same value. The dispatch reads the arguments, and the housekeeping asks the
   * column.
   *
   * @param tableName The table the entries lie in
   * @return The description to build a payload store with
   */
  public static JdbcPhaseTwoPayloadStore.EntriesNamingTheirPayload entriesNamingTheirPayload(
      final String tableName) {

    return JdbcPhaseTwoPayloadStore.EntriesNamingTheirPayload
        .inAColumn(tableName, JdbcPhaseTwoOutboxDispatcher.PAYLOAD_REFERENCE_COLUMN);

  }

  /**
   * Resolves the configured table name (<code>vanillabp.outbox.jdbc.table</code>, or else
   * {@link #DEFAULT_TABLE_NAME}).
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
   * What runs after the commit which wrote an entry: the dispatcher's poll, moved forward to
   * now. Crash recovery does not depend on it, because the poller finds the entry anyway. It
   * makes the ordinary case immediate.
   */
  private final Runnable dispatchWhatIsDue;

  private final String insertEntry;

  private final String selectPendingEntry;

  private final String replacePendingEntry;

  private final String selectAdapterIds;

  private final String countPendingEntries;

  private final String selectOldestPendingEntry;

  /**
   * Builds the store of one JDBC outbox: the statements for the given table, and the
   * helpers which the platform has to hand in. The dispatcher of that outbox creates the
   * table, not this store.
   *
   * @param connections How this platform hands out a connection which takes part in the
   *          running transaction
   * @param transaction How this platform answers whether a transaction is running, and how
   *          it tells the store that the transaction committed
   * @param tableName The table entries are written into
   * @param payloadStore Where the bytes of a call with a payload are written
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
   * The table this store was built for. A message or a housekeeping which has to name the
   * table asks this. Every outbox instance has a table of its own. So a message which named
   * the default would send a reader to the wrong place.
   *
   * @return The table this store writes its entries into
   */
  public String getTableName() {

    return tableName;

  }

  /**
   * The adapter ids for which the OPEN entries of one BPMN process wait. An id which is not
   * configured any more was renamed or removed too early. Both leave the workflow of a START
   * entry unstarted.
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
      // a startup diagnosis must not keep an application from booting. A store which cannot
      // tell leaves the question unanswered anyway
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
   * Counts the entries which wait for their dispatch. This is a single indexed count over
   * the outbox table. The table holds what has not run yet, plus what is kept until the
   * retention passes. It is the same table which the dispatcher polls.
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
      // a metric must never be the reason why an application fails. The gauge reports
      // nothing for this collection, and the next one tries again
      log.debug("Could not count the pending entries of the JDBC phase-two outbox", e);
      return OptionalLong.empty();
    } finally {
      release(connection);
    }

  }

  /**
   * How long the oldest waiting entry has been waiting, read from <code>CREATED_AT</code>.
   * That is the moment the entry was written. A replacing call sets it again, because the
   * row then has a younger operation.
   * <p>
   * The index which it reads spans STATUS and CREATED_AT. So the database answers from the
   * first entry of that index, and does not walk the waiting ones. Without the index the
   * answer costs as much as the count of the waiting entries, and both grow with the
   * backlog. Measured on PostgreSQL 16.15 in September 2026: 0.17 ms with a backlog of 1000
   * entries, 28 ms with 100000 and 78 ms with 500000. With the index it was 0.07 ms at
   * every size.
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
          // no row at all means that nothing waits. That zero is a measurement: the
          // outbox owes nothing
          return Optional
              .of(oldest == null
                  ? Duration.ZERO
                  : PhaseTwoOutbox.waitedSince(oldest.toInstant()));
        }
      }
    } catch (final SQLException e) {
      // a metric must never be the reason why an application fails. The gauge reports
      // nothing for this collection, and the next one tries again
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
    // what the unique constraint sees. An operation which must not be deduplicated takes
    // its own ID instead of a null, because not every database treats two nulls as
    // different values. A second entry next to one which a dispatch already took does the
    // same, because the key belongs to the entry which is on its way
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
        // a poller claimed the entry between the read and the update. The poller's dispatch
        // runs to its end, and this call becomes an entry of its own
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
        // the same value as in the arguments, in a column which the housekeeping can index
        statement.setString(8, call.payloadReference());
        statement.setString(9, idempotencyKey);
        statement.setString(10, dedupKey);
        statement.setTimestamp(11, Timestamp.from(now));
        statement.setTimestamp(12, Timestamp.from(now));
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      // two nodes schedule the same operation at the same moment. The read above found
      // nothing on both, and the constraint decided
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

    // the entry is in, so the bytes it names may follow, in this same transaction. Only
    // now, because a schedule which is discarded as a duplicate must leave nothing behind
    if (call.hasPayload()) {
      payloadStore.write(call);
    }

    dispatchAfterCommit();

    return true;

  }

  /**
   * A call which replaces takes the same way as any other. This is the one method which says
   * so. The mark travels in the call, so {@link #schedule(PhaseTwoCall)} reads it where the
   * entry is written.
   */
  @Override
  public boolean scheduleReplacingWhatIsStillWaiting(
      final PhaseTwoCall call) {

    return schedule(call.replacingWhatIsStillWaiting());

  }

  /**
   * Dispatches the entry right after the transaction was committed. The dispatcher's poller
   * covers recovery after a crash.
   */
  private void dispatchAfterCommit() {

    transaction.afterCommit(dispatchWhatIsDue);

  }

  /**
   * The entry with this key which still waits for its dispatch, or <code>null</code> where
   * the key is free. Nothing else can have the key. The dispatcher replaces it with the
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
   * All three writes use the transaction in which the call was scheduled. So no reader ever
   * sees one of them without the others, and a rollback takes them all. Removing the replaced
   * payload is safe for the same reason as the replacement itself. Nobody has read the
   * payload of an entry which no dispatch has claimed.
   *
   * @return Whether the entry was replaced. It is <code>false</code> where a poller claimed
   *         it in the meantime. Then the call becomes an entry of its own
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
      // the younger call brings its own payload, so the column follows the arguments
      statement.setString(5, call.payloadReference());
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setTimestamp(7, Timestamp.from(now));
      statement.setString(8, waiting.id());
      statement.setTimestamp(9, Timestamp.from(now));
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
   * Returns the connection to wherever it came from. If a connection cannot be returned,
   * that is logged and nothing more. The transaction it took part in commits or rolls back
   * its work. A schedule must not fail because of the way back.
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
   * What a store needs to know about the entry which a younger call meets: which row it is,
   * which payload it names, and whether an attempt of it has ended already.
   *
   * @param id The entry's own ID
   * @param args The arguments it persisted, holding the reference of its payload
   * @param attempts How many attempts of it have ended. Zero plus a free lease means that
   *          nobody has read it yet
   */
  private record PendingEntry(String id, String args, int attempts) {
  }

  /**
   * A younger call took the place of the entry which was waiting. This is logged at DEBUG,
   * for the same reason as a discard. The caller asked for it, and under a backlog it
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
   * dispatch had claimed that entry. The call becomes an entry of its own, so the handler is
   * called twice. That is worth a line. An application which counts its reports finds the
   * second one here.
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
   * The technical half of a discarded schedule. The store cannot tell which of the two causes
   * it was: a repeated dispatch, or an operation lost against one which still waits. So the
   * core reports it to the caller, and this line stays at DEBUG.
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
   * Whether the given exception signals a violated unique constraint, which means that an
   * operation with this key is already planned.
   *
   * @param e The exception raised by the insert
   * @return Whether the insert failed due to a duplicate key
   */
  private static boolean isDuplicateKey(
      final SQLException e) {

    // the JDBC driver of PostgreSQL does not map unique violations to the special subclass.
    // For such drivers, fall back to the standard SQL state class 23 (integrity constraint
    // violation)
    return (e instanceof SQLIntegrityConstraintViolationException) || ((e.getSQLState() != null) && e.getSQLState()
        .startsWith("23"));

  }

}
