package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DispatchOutcome;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the JDBC phase-two outbox
 * (see {@link JdbcPhaseTwoOutboxStore}) through the core's {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link JdbcPhaseTwoOutboxStore}) and</li>
 * <li>by a poller (crash recovery and retries) started by the platform once its
 * deployment is done, which sleeps until the earliest entry this store still owes
 * something to is due rather than polling on a rhythm - bounded by
 * <code>vanillabp.outbox.poll-interval</code> for work another node wrote down before it
 * died (see {@link DueEntryPoller}).</li>
 * </ul>
 * The poller uses a plain scheduled executor, so no framework scheduler is registered or
 * used and an application's own scheduling setup stays as it is. Due entries (status
 * {@link #STATUS_OPEN}) are claimed atomically (optimistic update incrementing the number
 * of attempts and leasing the entry for one
 * <code>vanillabp.outbox.attempt-frequency</code>), so multiple instances
 * do not dispatch the same entry concurrently. A dispatch which FAILS writes the next
 * attempt itself, at the growing distance of
 * {@link PhaseTwoOutboxProperties#attemptDelay(int)}
 * - doubling per attempt up to <code>vanillabp.outbox.max-attempt-frequency</code>, so
 * an outage of hours drains itself when the BPMS comes back. On successful dispatch the entry is
 * marked {@link #STATUS_DONE} - it stays in the table for support to read and is
 * deleted asynchronously once
 * <code>vanillabp.outbox.retention</code> passed. After
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts an entry is
 * marked {@link #STATUS_BLOCKED} and has to be cleaned up manually.
 * <p>
 * <strong>Several entries leave at the same time</strong>, and the workflow aggregate
 * decides which of them may: the poller claims the due entries in the order they were
 * written and hands each of them to the lane of its aggregate
 * ({@link DispatchLanes}). Entries of one aggregate therefore keep their order while
 * entries of different aggregates run at the same time. What this does not order is an
 * entry which FAILED: it waits for its backoff, and the next entry of the same aggregate
 * passes it meanwhile - the same as on a single thread, where a failed entry is put back
 * as well.
 * <p>
 * <strong>Cluster safety:</strong> multiple application instances (pods) may poll
 * concurrently without any distributed lock: the SELECT may return the same due
 * entries on several instances, but the claim is an optimistic UPDATE
 * (<code>WHERE ID = ? AND ATTEMPTS = ?</code>) - exactly one instance wins the
 * claim and dispatches the entry, the others simply skip it. The retention cleanup
 * is a plain idempotent DELETE.
 * <p>
 * The outbox table and the table holding the payloads of the calls which carry one are
 * created on startup unless <code>vanillabp.outbox.create-schema</code> is disabled for manually managed
 * schemas - in that case also create the unique constraint on
 * <code>DEDUP_KEY</code> yourself (the storage-level deduplication of the
 * outbox contract, spanning the entries still waiting for their dispatch; see
 * {@link JdbcPhaseTwoOutboxStore}). The DDL is kept portable: table existence is checked via JDBC
 * metadata (<code>CREATE TABLE IF NOT EXISTS</code> is not supported by Oracle and
 * SQL Server), the timestamp type is chosen per database (SQL Server's
 * <code>TIMESTAMP</code> is a row version, MySQL's has auto-initialization quirks
 * and a 2038 range limit) and the idempotency key is limited to 512 characters so
 * MySQL's unique-index key-length limit (3072 bytes with utf8mb4) is respected.
 */
@Slf4j
public class JdbcPhaseTwoOutboxDispatcher {

  public static final String STATUS_OPEN = "OPEN";

  public static final String STATUS_DONE = "DONE";

  public static final String STATUS_BLOCKED = "BLOCKED";

  /**
   * The due entries, oldest first. The order is what the lanes turn into the order of
   * one aggregate: a lane runs what it is handed in the order it is handed, so the
   * entries of one aggregate have to reach it the way they were written.
   */
  private static final String SELECT_DUE_ENTRIES = """
      SELECT ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, ATTEMPTS, \
      CREATED_AT \
      FROM %s \
      WHERE STATUS = '%s' AND NEXT_ATTEMPT_AT <= ? AND ATTEMPTS < ? \
      ORDER BY CREATED_AT""";

  /**
   * When the earliest entry waiting for its dispatch wants to be looked at. It is the
   * select above with its time bound dropped, which is what keeps the two in step: an
   * entry this does not see is an entry that one would not pick up either, and a BLOCKED
   * entry is in neither, because it waits for a person rather than for a clock.
   */
  private static final String SELECT_NEXT_ATTEMPT = """
      SELECT MIN(NEXT_ATTEMPT_AT) \
      FROM %s \
      WHERE STATUS = '%s' AND ATTEMPTS < ?""";

  /**
   * When the oldest dispatched entry may be deleted, asked as the moment it was dispatched
   * so the retention can be added to it in Java rather than in each database's own date
   * arithmetic.
   */
  private static final String SELECT_OLDEST_DONE = """
      SELECT MIN(DONE_AT) \
      FROM %s \
      WHERE STATUS = '%s'""";

  private static final String CLAIM_ENTRY = """
      UPDATE %s \
      SET ATTEMPTS = ATTEMPTS + 1, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND ATTEMPTS = ?""";

  /**
   * What the claim won, read again by its ID - see {@link #claim(Connection, Entry)}
   * for why the row is not the one the select of the due entries returned.
   */
  private static final String SELECT_CLAIMED_ENTRY = """
      SELECT WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, CREATED_AT \
      FROM %s WHERE ID = ?""";

  /**
   * Marking an entry DONE closes its deduplication window: DEDUP_KEY takes the entry's
   * own ID, so a repetition of the same operation can be planned again, while
   * IDEMPOTENCY_KEY keeps the key readable for whoever reads the table during support.
   */
  private static final String MARK_ENTRY_DONE = """
      UPDATE %s \
      SET STATUS = '%s', DONE_AT = ?, DEDUP_KEY = ID \
      WHERE ID = ?""";

  /**
   * Blocking releases DEDUP_KEY the way marking an entry DONE does, and for a reason
   * which is easy to miss: the key is what refuses a second schedule of the same
   * operation, so a blocked entry which kept it would silence the very repetition the
   * application needs - it would ask, the outbox would answer no, and that answer looks
   * exactly like a correct deduplication. The row stays for whoever repairs it, and the
   * new attempt of the operation is a row of its own.
   */
  private static final String MARK_ENTRY_BLOCKED = """
      UPDATE %s \
      SET STATUS = '%s', DEDUP_KEY = ID \
      WHERE ID = ?""";

  /**
   * Moves the next attempt of a claimed entry closer than the configured backoff, for a
   * dispatch which said how long its reason lasts. The claim already counted the
   * attempt, so this shortens the wait and nothing else.
   */
  private static final String RESCHEDULE_ENTRY = """
      UPDATE %s \
      SET NEXT_ATTEMPT_AT = ? \
      WHERE ID = ?""";

  private static final String DELETE_EXPIRED_DONE_ENTRIES = """
      DELETE FROM %s \
      WHERE STATUS = '%s' AND DONE_AT < ?""";

  /**
   * The index the poller asks its question along. STATUS first and the timestamp second, which is
   * the order both the aggregate and the select of the due entries read them in.
   */
  private static final String CREATE_DUE_INDEX = "CREATE INDEX %s_DUE ON %s (STATUS, NEXT_ATTEMPT_AT)";

  /**
   * The index the retention deletes along. A second one rather than more columns in the first,
   * because the two questions filter the same STATUS and order by different timestamps.
   */
  private static final String CREATE_AGE_INDEX = "CREATE INDEX %s_AGE ON %s (STATUS, DONE_AT)";

  private final JdbcConnectionAccess connections;

  private final PhaseTwoOutboxProperties properties;

  private final Supplier<PhaseTwoRouter> phaseTwoRouter;

  /**
   * What a blocked entry is counted into. A supplier and not the value itself, because
   * metrics are optional on both platforms and the bean may be resolved later than this
   * dispatcher is built.
   */
  private final Supplier<VanillaBpMetrics> metrics;

  /**
   * Which store an operator reads in the meters of this dispatcher. The platform names
   * its own class, because that is the bean an application sees.
   */
  private final String storeName;

  private final JdbcPhaseTwoPayloadStore payloadStore;

  private final String tableName;

  private final String selectDueEntries;

  private final String selectNextAttempt;

  private final String selectOldestDone;

  private final String claimEntry;

  private final String selectClaimedEntry;

  private final String markEntryDone;

  private final String markEntryBlocked;

  private final String rescheduleEntry;

  private final String deleteExpiredDoneEntries;

  private final DueEntryPoller poller;

  private final DispatchLanes lanes;

  /**
   * A due outbox entry read from the database.
   *
   * @param id The entry's ID
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param operation The name of the scheduled {@link PhaseOperation}
   * @param aggregateId The workflow aggregate's ID in serialized form
   * @param adapterId The ID of the elected BPMS adapter (may be <code>null</code>)
   * @param serializedArgs The arguments the call was scheduled with
   * @param attempts The number of dispatch attempts so far
   * @param createdAt When the entry was written - a replacing call sets it anew,
   *          because the row then carries a younger operation
   */
  private record Entry(
                       String id,
                       String workflowModuleId,
                       String bpmnProcessId,
                       String operation,
                       String aggregateId,
                       String adapterId,
                       String serializedArgs,
                       int attempts,
                       Instant createdAt) {

    /**
     * What this entry is ordered by: its workflow aggregate. An entry which names none -
     * a broadcast signal does not - is ordered by its BPMN process instead, so two
     * signals of one process still leave in the order they were planned.
     *
     * @return The key deciding which lane dispatches this entry
     */
    String orderingKey() {

      return aggregateId == null
          ? workflowModuleId
              + "|"
              + bpmnProcessId
          : workflowModuleId
              + "|"
              + bpmnProcessId
              + "|"
              + aggregateId;

    }

  }

  /**
   * @param connections How this platform hands out a connection, used here outside any
   *          transaction of the application
   * @param properties The bound <code>vanillabp.outbox</code> section
   * @param tableName The table polled - the one its store writes into
   * @param payloadStore Where the bytes of a call which carries a payload lie
   * @param phaseTwoRouter The router a claimed entry is handed to
   * @param metrics What a blocked entry is counted into
   * @param storeName Which store the meters of this dispatcher name
   */
  public JdbcPhaseTwoOutboxDispatcher(
      final JdbcConnectionAccess connections,
      final PhaseTwoOutboxProperties properties,
      final String tableName,
      final JdbcPhaseTwoPayloadStore payloadStore,
      final Supplier<PhaseTwoRouter> phaseTwoRouter,
      final Supplier<VanillaBpMetrics> metrics,
      final String storeName) {

    this.connections = connections;
    this.properties = properties;
    this.tableName = tableName;
    this.payloadStore = payloadStore;
    this.phaseTwoRouter = phaseTwoRouter;
    this.metrics = metrics;
    this.storeName = storeName;
    this.selectDueEntries = SELECT_DUE_ENTRIES.formatted(tableName, STATUS_OPEN);
    this.selectNextAttempt = SELECT_NEXT_ATTEMPT.formatted(tableName, STATUS_OPEN);
    this.selectOldestDone = SELECT_OLDEST_DONE.formatted(tableName, STATUS_DONE);
    this.claimEntry = CLAIM_ENTRY.formatted(tableName);
    this.selectClaimedEntry = SELECT_CLAIMED_ENTRY.formatted(tableName);
    this.markEntryDone = MARK_ENTRY_DONE.formatted(tableName, STATUS_DONE);
    this.markEntryBlocked = MARK_ENTRY_BLOCKED.formatted(tableName, STATUS_BLOCKED);
    this.rescheduleEntry = RESCHEDULE_ENTRY.formatted(tableName);
    this.deleteExpiredDoneEntries = DELETE_EXPIRED_DONE_ENTRIES.formatted(tableName, STATUS_DONE);
    this.poller = new DueEntryPoller(
        "vanillabp-outbox", properties.getPollInterval(), this::poll, this::earliestDueAt);
    this.lanes = new DispatchLanes("vanillabp-outbox-dispatch", properties.getDispatchThreads());

  }

  /**
   * Creates the outbox table and the payload table (unless the application manages its
   * schema itself, in which case their existence is verified instead).
   * <p>
   * Called by the platform BEFORE the deployment pipeline runs, because a workflow may
   * be started as soon as the application is up and the entry needs its table.
   */
  public void prepareSchema() {

    if (properties.isCreateSchema()) {
      createTableIfNotExists();
      payloadStore.createSchemaIfNotExists();
    } else {
      // the application creates its schema itself - a missing table is then a
      // deployment which forgot to apply the migration, and it is said at startup instead of at
      // the first workflow start
      validateTableExists();
      payloadStore.validateSchemaExists();
    }

  }

  /**
   * Starts polling. The platform calls this once the BPMN resources are deployed and
   * workflow processing has started, so nothing recovered is carried to a BPMS which has
   * not seen the models yet.
   */
  public void start() {

    poller.start();

  }

  /**
   * Stops the poller and the lanes. What a lane was still holding stays OPEN in the
   * table, so the next start of this node or a poll of another one takes it.
   */
  public void stop() {

    poller.stop();
    lanes.stop();

  }

  /**
   * Pulls the next poll forward to now (used right after a commit, where the entry just
   * written wants to go out at once).
   */
  public void triggerPoll() {

    poller.somethingIsDueAt(Instant.now());

  }

  /**
   * When this store owes something: the due time of the earliest entry waiting for its
   * dispatch, or the moment the oldest dispatched entry may be deleted, whichever comes
   * first. Two aggregates over one connection, each answered from the index this store creates
   * over STATUS and that question's timestamp.
   *
   * @return The earliest of the two moments, or <code>null</code> where the table holds
   *         neither
   */
  private Instant earliestDueAt() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      final var nextAttempt = earliest(connection, selectNextAttempt, properties.getBlockAfterAttempts());
      final var oldestDone = earliest(connection, selectOldestDone, null);
      final var retentionRunsOut = oldestDone == null
          ? null
          : oldestDone.plus(properties.getRetention());
      if (nextAttempt == null) {
        return retentionRunsOut;
      }
      if (retentionRunsOut == null) {
        return nextAttempt;
      }
      return nextAttempt.isBefore(retentionRunsOut) ? nextAttempt : retentionRunsOut;
    } catch (final SQLException e) {
      // the poll which follows reports the same problem with its own message, and a poller
      // which stops asking is worse than one which asks at the configured cap
      log.debug("Could not read when the next phase-two outbox entry of table '{}' is due", tableName, e);
      return null;
    } finally {
      release(connection);
    }

  }

  private Instant earliest(
      final Connection connection,
      final String query,
      final Integer attemptsBelow) throws SQLException {

    try (var statement = connection.prepareStatement(query)) {
      if (attemptsBelow != null) {
        statement.setInt(1, attemptsBelow);
      }
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        final var earliest = resultSet.getTimestamp(1);
        return earliest == null ? null : earliest.toInstant();
      }
    }

  }

  private void createTableIfNotExists() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      // existence is checked via JDBC metadata since 'CREATE TABLE IF NOT EXISTS'
      // is not supported by all databases (e.g. Oracle, SQL Server)
      if (JdbcSchema.tableExists(connection, tableName)) {
        reportMissingIndexes(connection);
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(buildCreateTable(connection, tableName));
        statement.executeUpdate(CREATE_DUE_INDEX.formatted(tableName, tableName));
        statement.executeUpdate(CREATE_AGE_INDEX.formatted(tableName, tableName));
      }
    } catch (final SQLException e) {
      if (createdConcurrently()) {
        return;
      }
      throw new IllegalStateException(
          """
              Could not create the phase-two outbox table '%s'! Set 'vanillabp.outbox.create-schema' \
              to 'false' and manage the schema manually if the DDL is not suitable for your database."""
              .formatted(tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Whether the DDL failed because another instance created the table between the check and the
   * statement. Two instances starting together (a rolling deployment, a scale-up from zero) both
   * see no table and both create it, and the loser's boot must not end over it - it just has
   * nothing left to do (see {@link JdbcSchema#tableExistsQuietly(Connection, String)}).
   *
   * @return Whether the table is there now
   */
  private boolean createdConcurrently() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      if (!JdbcSchema.tableExistsQuietly(connection, tableName)) {
        return false;
      }
      log.debug(
          "The phase-two outbox table '{}' was created by another instance starting at the same moment",
          tableName);
      return true;
    } catch (final SQLException e) {
      return false;
    } finally {
      release(connection);
    }

  }

  /**
   * Verifies that the outbox table exists, for an application creating its schema itself.
   * The message names the table, the property which would have created it and the
   * artifact carrying the statements.
   *
   * @throws IllegalStateException If the table is missing
   */
  private void validateTableExists() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        reportMissingIndexes(connection);
        return;
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Could not check whether the phase-two outbox table '%s' exists!".formatted(tableName), e);
    } finally {
      release(connection);
    }
    throw new IllegalStateException(
        """
            The phase-two outbox table '%s' does not exist! Starting a workflow on a remote BPMS \
            writes an entry into it inside the caller's transaction, so without the table nothing \
            can be started. Either
            - apply the schema of VanillaBP with your migration tool: the artifact \
            'io.vanillabp:vanillabp-schema' ships the Liquibase changelog \
            'vanillabp/schema/changelog.xml' and the SQL generated from it for Flyway, or
            - let VanillaBP create the table by setting 'vanillabp.outbox.create-schema' to \
            'true' (the default)."""
            .formatted(tableName));

  }

  /**
   * Names the indexes this table needs and does not have, with the statement which adds each of
   * them. A table created by an earlier version of VanillaBP has neither, and the poller then asks
   * its question as a sequential scan growing with everything the table ever held - which is a cost
   * nobody sees until the table is large. A warning and not a failure: the application runs
   * correctly without them, and creating an index on a large table is a decision with a lock on it,
   * not something a boot should do behind its operator's back.
   *
   * @param connection The connection to the database holding the table
   */
  private void reportMissingIndexes(
      final Connection connection) {

    final var missing = new ArrayList<String>();
    if (!JdbcSchema
        .indexExists(
            connection, tableName, tableName
                + "_DUE")) {
      missing.add(CREATE_DUE_INDEX.formatted(tableName, tableName));
    }
    if (!JdbcSchema
        .indexExists(
            connection, tableName, tableName
                + "_AGE")) {
      missing.add(CREATE_AGE_INDEX.formatted(tableName, tableName));
    }
    if (missing.isEmpty()) {
      return;
    }
    log
        .warn(
            """
                The phase-two outbox table '{}' is missing {} index(es) this version reads by, so \
                every poll of it scans the whole table. Run:
                  {};
                Until then the outbox works and gets slower as the table grows.""",
            tableName,
            missing.size(),
            String.join(";\n  ", missing));

  }

  /**
   * Builds the CREATE TABLE statement using a timestamp type suitable for the
   * database: SQL Server's <code>TIMESTAMP</code> is a row version (not a
   * date-time), MySQL's <code>TIMESTAMP</code> has auto-initialization quirks and
   * ends in 2038. The idempotency key is limited to 512 characters (2048 bytes
   * with utf8mb4) to stay below MySQL's unique-index key-length limit of 3072
   * bytes.
   *
   * @param connection The connection used to detect the database
   * @param tableName The table to create
   * @return The CREATE TABLE statement
   */
  private static String buildCreateTable(
      final Connection connection,
      final String tableName) throws SQLException {

    final var product = connection
        .getMetaData()
        .getDatabaseProductName()
        .toLowerCase();
    final String timestampType;
    if (product.contains("microsoft")) {
      timestampType = "DATETIME2";
    } else if (product.contains("mysql") || product.contains("mariadb")) {
      timestampType = "DATETIME(6)";
    } else {
      timestampType = "TIMESTAMP";
    }
    return """
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
        CREATED_AT %s NOT NULL, \
        ATTEMPTS INT NOT NULL, \
        NEXT_ATTEMPT_AT %s NOT NULL, \
        DONE_AT %s)"""
        .formatted(
            tableName,
            timestampType,
            timestampType,
            timestampType);

  }

  /**
   * Claims all due entries and hands each of them to the lane of its aggregate, then
   * deletes DONE entries whose retention passed. Exceptions are caught to keep the
   * poller alive.
   * <p>
   * The claim is what this thread does and the dispatch is what a lane does, and the
   * order matters: an entry is claimed before it is handed over, so the claim of the
   * next poll - here or on another node - finds it leased and leaves it alone.
   * <p>
   * A lane whose queue is full makes this thread wait, holding the connection it claims
   * with. That is the back pressure of a backlog which arrives faster than it leaves, and
   * waiting is what keeps the backlog in the table, where it can be read.
   */
  private synchronized void poll() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      for (final var entry : loadDueEntries(connection)) {
        final var claimed = claim(connection, entry);
        if (claimed != null) {
          lanes.runInOrderOf(claimed.orderingKey(), () -> dispatch(claimed));
        }
      }
      cleanupDoneEntries(connection);
    } catch (final Exception e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    } finally {
      release(connection);
    }

  }

  private List<Entry> loadDueEntries(
      final Connection connection) throws SQLException {

    final var entries = new ArrayList<Entry>();
    try (var statement = connection.prepareStatement(selectDueEntries)) {
      statement.setTimestamp(1, Timestamp.from(Instant.now()));
      statement.setInt(2, properties.getBlockAfterAttempts());
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          entries.add(new Entry(
              resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet
                  .getString(5), resultSet.getString(6), resultSet.getString(7), resultSet.getInt(8), writtenAt(
                      resultSet, 9)));
        }
      }
    }
    return entries;

  }

  /**
   * Claims an entry using an optimistic update: incrementing the number of attempts
   * and setting the backoff makes concurrent pollers (or other instances) skip the
   * entry, and a failed dispatch is retried automatically once the backoff elapsed.
   * <p>
   * What the claim won is then READ AGAIN, and the dispatch works with that. Between
   * the select of the due entries and this update the row may have been replaced by a
   * younger call ({@link JdbcPhaseTwoOutboxStore}), and the entry read a moment ago names
   * the payload that call removed - a dispatch built from it would hand the handler
   * nothing where bytes were promised. It is one read by primary key per dispatch
   * attempt, which is the cost of the rule that a dispatch reads what was written
   * last.
   *
   * @param connection The connection to be used
   * @param entry The entry to be claimed
   * @return The entry as it stands now, or <code>null</code> where another poller won
   *         it
   */
  private Entry claim(
      final Connection connection,
      final Entry entry) throws SQLException {

    try (var statement = connection.prepareStatement(claimEntry)) {
      // the claim leases the entry for one attempt-frequency, which is what keeps other
      // pollers off it while this dispatch runs. The growing backoff belongs to a FAILED
      // dispatch and is written there, so a poller which dies mid-dispatch does not
      // inherit the long distance of an attempt nobody made
      statement.setTimestamp(1, Timestamp.from(Instant.now().plus(properties.getAttemptFrequency())));
      statement.setString(2, entry.id());
      statement.setInt(3, entry.attempts());
      if (statement.executeUpdate() != 1) {
        return null;
      }
    }
    try (var statement = connection.prepareStatement(selectClaimedEntry)) {
      statement.setString(1, entry.id());
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? new Entry(
                entry.id(), resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet
                    .getString(4), resultSet.getString(5), resultSet.getString(6), entry.attempts(), writtenAt(
                        resultSet, 7))
            : null;
      }
    }

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   * <p>
   * This runs on the lane of the entry's aggregate, and it holds no connection while the
   * router works: the dispatch calls a BPMS over the network, and a connection held for
   * that long would make the pool the limit of how many entries may travel at once.
   *
   * @param entry The claimed entry
   */
  private void dispatch(
      final Entry entry) {

    final var args = PhaseTwoCall.deserializeArgs(entry.serializedArgs());
    final var payloadReference = args.get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    try {
      // entry.attempts() holds the count BEFORE this claim - a value > 0 means the
      // entry was dispatched before (recovered/retried): the router then runs the
      // START re-dispatch mitigation. The operation travels as its persisted name -
      // the router resolves it in the operation registry (an unknown name yields a
      // guiding error and leaves the entry for operations)
      //
      // reading the payload is the one extra read this form costs, and only for an
      // entry which names one: a lookup by primary key, once per dispatch attempt
      final var payload = payloadReference == null
          ? null
          : payloadStore.read(payloadReference);
      dispatchMeasuringTheWait(
          PhaseTwoCall
              .forDispatch(
                  entry.operation(), entry.workflowModuleId(), entry.bpmnProcessId(), entry
                      .aggregateId(),
                  entry.adapterId(), args, payload),
          entry.attempts() > 0,
          entry.createdAt());
    } catch (final Exception e) {
      reportFailedDispatch(entry, e);
      return;
    }
    markDone(entry);
    // the entry is dispatched, so its bytes have done their work. Removed AFTER the
    // entry was marked, never before: a crash in between leaves a row the housekeeping
    // deletes, while the other order would leave an entry whose payload is gone
    if (payloadReference != null) {
      payloadStore.remove(payloadReference);
    }

  }

  /**
   * Writes down what a failed dispatch means for the entry: blocked where repeating
   * cannot help or where the attempts are used up, and a new due time otherwise.
   *
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void reportFailedDispatch(
      final Entry entry,
      final Exception e) {

    // the adapter said that repeating cannot help - blocked right away
    // instead of after the configured attempts
    if (PhaseTwoPermanentFailure.isPermanent(e)) {
      update(markEntryBlocked, statement -> statement.setString(1, entry.id()));
      countBlockedEntry(entry.operation(), true);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked and has "
              + "to be cleaned up manually!",
          entry.operation(),
          entry.bpmnProcessId(),
          entry.workflowModuleId(),
          entry.aggregateId(),
          entry.id(),
          e);
      return;
    }
    if (entry.attempts() + 1 >= properties.getBlockAfterAttempts()) {
      update(markEntryBlocked, statement -> statement.setString(1, entry.id()));
      countBlockedEntry(entry.operation(), false);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
          entry.operation(),
          entry.bpmnProcessId(),
          entry.workflowModuleId(),
          entry.aggregateId(),
          entry.attempts() + 1,
          entry.id(),
          e);
      return;
    }
    final var retryAfter = PhaseTwoRetryLater.retryAfter(e);
    if (retryAfter != null) {
      // the dispatch knows when asking again can help - a workflow the BPMS has not
      // made searchable yet is the case - so the entry waits that long instead of the
      // configured backoff. What ends a reason which never goes away is the attempts
      // counted above, not this due time
      rescheduleAt(entry, Instant.now().plus(retryAfter));
      log.info(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
              + "run yet - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used): {}",
          entry.operation(),
          entry.bpmnProcessId(),
          entry.workflowModuleId(),
          entry.aggregateId(),
          entry.id(),
          retryAfter,
          entry.attempts() + 1,
          properties.getBlockAfterAttempts(),
          e.getMessage());
      return;
    }
    // attempts() is the count BEFORE this claim, so attemptDelay(0) is the distance
    // after the first failure: close, because most failures are momentary
    final var retryIn = properties.attemptDelay(entry.attempts());
    rescheduleAt(entry, Instant.now().plus(retryIn));
    log.warn(
        "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
            + "failed - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used)",
        entry.operation(),
        entry.bpmnProcessId(),
        entry.workflowModuleId(),
        entry.aggregateId(),
        entry.id(),
        retryIn,
        entry.attempts() + 1,
        properties.getBlockAfterAttempts(),
        e);

  }

  /**
   * Ticks the entry off and tells the poller when the row may be deleted. The poller is
   * told for the reason {@link #rescheduleAt(Entry, Instant)} tells it: the dispatch does
   * not run on it, so what this write leaves behind is news to it.
   *
   * @param entry The entry which was dispatched
   */
  private void markDone(
      final Entry entry) {

    final var doneAt = Instant.now();
    update(markEntryDone, statement -> {
      statement.setTimestamp(1, Timestamp.from(doneAt));
      statement.setString(2, entry.id());
    });
    poller.somethingIsDueAt(doneAt.plus(properties.getRetention()));

  }

  /**
   * Writes when the next attempt of an entry is due, and tells the poller about it.
   * <p>
   * The poller has to be told because the dispatch does not run on it: it decided how long
   * to rest as this attempt began, from the lease the claim wrote. A due time closer than
   * that lease - the one an adapter names for a workflow its BPMS has not made searchable
   * yet - would otherwise be waited out to the end of the lease.
   *
   * @param entry The entry whose dispatch failed
   * @param nextAttempt When it is to be read again
   */
  private void rescheduleAt(
      final Entry entry,
      final Instant nextAttempt) {

    update(rescheduleEntry, statement -> {
      statement.setTimestamp(1, Timestamp.from(nextAttempt));
      statement.setString(2, entry.id());
    });
    poller.somethingIsDueAt(nextAttempt);

  }

  /**
   * Runs one statement on a connection of its own. A lane holds no connection between
   * two entries, so every write of a dispatch borrows one for the moment it needs it.
   * <p>
   * A failure here is logged and not thrown: what it costs is a repetition of an entry
   * whose dispatch already happened, which the contract of the outbox allows, and
   * throwing would end the lane instead.
   *
   * @param sql The statement to run
   * @param arguments What to put into it
   */
  private void update(
      final String sql,
      final StatementArguments arguments) {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(sql)) {
        arguments.setOn(statement);
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      log.error("Could not update an entry of the phase-two outbox table '{}'", tableName, e);
    } finally {
      release(connection);
    }

  }

  /**
   * What a statement of this dispatcher needs beyond its text.
   */
  @FunctionalInterface
  private interface StatementArguments {

    void setOn(
        java.sql.PreparedStatement statement) throws SQLException;

  }

  /**
   * Hands the call to the core's router and reports how long its entry waited for this
   * attempt, whether the attempt succeeded or threw. The wait is counted from the moment
   * the entry was written, so a repeated attempt reports the whole wait of the operation
   * and not the distance since the last try.
   * <p>
   * One report per entry, and the lanes change nothing about that: an entry which travels
   * beside another one waited as long as its own row says.
   *
   * @param call The call to dispatch
   * @param previouslyAttempted Whether the entry was dispatched before
   * @param writtenAt When the entry was written, or <code>null</code> where the row
   *          carries no such moment
   */
  private void dispatchMeasuringTheWait(
      final PhaseTwoCall call,
      final boolean previouslyAttempted,
      final Instant writtenAt) {

    try {
      phaseTwoRouter
          .get()
          .dispatch(call, previouslyAttempted);
    } catch (final RuntimeException e) {
      reportWait(writtenAt, DispatchOutcome.FAILED);
      throw e;
    }
    reportWait(writtenAt, DispatchOutcome.SUCCEEDED);

  }

  /**
   * @param writtenAt When the entry was written
   * @param outcome How the attempt ended
   */
  private void reportWait(
      final Instant writtenAt,
      final DispatchOutcome outcome) {

    if (writtenAt == null) {
      return;
    }
    metrics
        .get()
        .outboxDispatchEnded(
            storeName,
            outcome,
            PhaseTwoOutbox
                .waitedSince(writtenAt)
                .toNanos());

  }

  /**
   * When an entry was written, as its row says. The column is NOT NULL in the table
   * VanillaBP creates, so nothing comes back only where the application brought a table
   * of its own which leaves it empty. The attempt of such an entry is not measured, which
   * is better than measuring it from now.
   *
   * @param resultSet The row being read
   * @param column The column holding the moment
   * @return The moment or <code>null</code>
   */
  private static Instant writtenAt(
      final ResultSet resultSet,
      final int column) throws SQLException {

    final var written = resultSet.getTimestamp(column);
    return written == null ? null : written.toInstant();

  }

  /**
   * Counts an entry this store gave up on. The gauge of waiting entries drops at the
   * same moment, so without this counter the only number an operator watches would move
   * as if things had got better.
   *
   * @param operation The persisted name of the operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   */
  private void countBlockedEntry(
      final String operation,
      final boolean permanent) {

    metrics
        .get()
        .outboxEntryBlocked(storeName, operation, permanent);

  }

  /**
   * Deletes successfully dispatched (DONE) entries whose retention period passed - the
   * asynchronous cleanup of the "DONE instead of delete" contract - and the payloads
   * which outlived the same period.
   *
   * @param connection The connection to be used
   */
  private void cleanupDoneEntries(
      final Connection connection) throws SQLException {

    final var expiredBefore = Instant.now().minus(properties.getRetention());
    try (var statement = connection.prepareStatement(deleteExpiredDoneEntries)) {
      statement.setTimestamp(1, Timestamp.from(expiredBefore));
      statement.executeUpdate();
    }
    // what a crash between the two writes of a schedule left behind, and the payload of
    // an entry blocked longer than the retention. Both are rows nobody will read again
    payloadStore.removeOlderThan(expiredBefore);

  }

  /**
   * Returns the connection to wherever it came from, see
   * {@link JdbcConnectionAccess#release(Connection)}.
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

}
