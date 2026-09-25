package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcDialect;
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
 * {@link #STATUS_OPEN}) are claimed atomically (an optimistic update writing
 * <code>LEASED_BY</code> and <code>LEASED_UNTIL</code>, the lease lasting one
 * <code>vanillabp.outbox.attempt-frequency</code>), so multiple instances
 * do not dispatch the same entry concurrently. <strong>A claimed entry renews its
 * lease</strong> ({@link DispatchLease}), from the claim until its dispatch is over, so an
 * entry which waits for its lane or travels longer than that distance stays the claim of
 * the node carrying it instead of being taken by the next poll. A
 * dispatch which FAILS writes the next attempt itself, at the growing distance of
 * {@link PhaseTwoOutboxProperties#attemptDelay(int)}
 * - doubling per attempt up to <code>vanillabp.outbox.max-attempt-frequency</code>, so
 * an outage of hours drains itself when the BPMS comes back. On successful dispatch the entry is
 * marked {@link #STATUS_DONE} - it stays in the table for support to read and is
 * deleted asynchronously once
 * <code>vanillabp.outbox.retention</code> passed. After
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts an entry is
 * marked {@link #STATUS_BLOCKED} and has to be cleaned up manually.
 * <p>
 * <strong><code>ATTEMPTS</code> counts attempts, not claims.</strong> The column is
 * written when an attempt ENDED - together with the mark which says how it ended - so a
 * dispatch which takes its time uses up no attempt budget, and
 * <code>block-after-attempts</code> blocks an entry which keeps failing rather than one
 * which is slow.
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
 * (<code>WHERE ID = ? AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL &lt;= ?)</code>) - exactly
 * one instance wins the claim and dispatches the entry, the others simply skip it. A node
 * which dies while dispatching stops renewing, its lease runs out, and the next poll of
 * any node takes the entry over. The retention cleanup is a plain idempotent DELETE.
 * <p>
 * Where a node which is still alive loses its entry that way, its dispatch runs to its end
 * and the result is dropped: the writes which say how an attempt ended carry
 * <code>LEASED_BY</code> in their condition, so only the node holding the entry writes
 * them. Both nodes say in their log what happened, and the second delivery is the
 * at-least-once residual this outbox documents.
 * <p>
 * A write the database could not run at all is the third answer, and it is kept apart from
 * both of the above: nothing was written, so the entry keeps its
 * status, its due time and its lease, and the next poll reads it once that lease runs out.
 * It therefore keeps its payload as well, whatever the attempt did - an entry which is going
 * to be dispatched again needs those bytes.
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
public class JdbcPhaseTwoOutboxDispatcher implements OutboxHousekeeping.Store {

  /**
   * What the <code>STATUS</code> column holds while an entry still has to be dispatched -
   * the state every entry is written in, and the only one a poll reads. An entry somebody
   * is dispatching right now is OPEN as well and is kept apart by its lease.
   * <p>
   * The three values are constants because they are written into SQL here, read by the
   * store next to this class and looked up by their name in the tests. A test which wrote
   * <code>'OPEN'</code> itself would keep passing after a rename.
   */
  public static final String STATUS_OPEN = "OPEN";

  /**
   * What the <code>STATUS</code> column holds after the operation reached the BPMS. The
   * row stays for <code>vanillabp.outbox.retention</code> so support can still read what
   * was dispatched, and the housekeeping deletes it afterwards.
   */
  public static final String STATUS_DONE = "DONE";

  /**
   * What the <code>STATUS</code> column holds after <code>block-after-attempts</code>
   * failed attempts: the entry is not read by any poll any more and waits for a person.
   * Nothing in VanillaBP moves it back, which is the point - an entry which failed that
   * often is broken rather than unlucky.
   */
  public static final String STATUS_BLOCKED = "BLOCKED";

  /**
   * The due entries, oldest first. The order is what the lanes turn into the order of
   * one aggregate: a lane runs what it is handed in the order it is handed, so the
   * entries of one aggregate have to reach it the way they were written.
   * <p>
   * An entry somebody is dispatching right now is out, by its lease. Its
   * <code>NEXT_ATTEMPT_AT</code> says the same thing, because a claim and every renewal
   * write both, but the lease is what decides - which is what keeps the rule in one place
   * when a due time is written from somewhere else.
   */
  private static final String SELECT_DUE_ENTRIES = """
      SELECT ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, ATTEMPTS, \
      CREATED_AT, LEASED_BY \
      FROM %s \
      WHERE STATUS = '%s' AND NEXT_ATTEMPT_AT <= ? AND ATTEMPTS < ? \
      AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?) \
      ORDER BY CREATED_AT""";

  /**
   * When the earliest entry waiting for its dispatch wants to be looked at. It is the
   * select above with its time bounds dropped, which is what keeps the two in step: an
   * entry this does not see is an entry that one would not pick up either, and a BLOCKED
   * entry is in neither, because it waits for a person rather than for a clock.
   * <p>
   * An entry being dispatched right now answers with the end of its lease, not with "due":
   * the claim and every renewal push <code>NEXT_ATTEMPT_AT</code> along with
   * <code>LEASED_UNTIL</code>. Without that the poller would be told "due now" for as long
   * as the dispatch runs and would ask again at its shortest sleep, which is the repeated
   * question decision 19 in the repository's DECISIONS.md forbids.
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

  /**
   * Takes the entry, for as long as the lease lasts. The condition is the lease and not the
   * number of attempts: a claim must say "nobody is carrying this right now", and the
   * attempts say something else since they are written when an attempt ended.
   * <p>
   * <code>NEXT_ATTEMPT_AT</code> travels with the lease so the poller's sleep is computed
   * from a moment where there is something to do again - see {@link #SELECT_NEXT_ATTEMPT}.
   */
  private static final String CLAIM_ENTRY = """
      UPDATE %s \
      SET LEASED_BY = ?, LEASED_UNTIL = ?, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND STATUS = '%s' AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?)""";

  /**
   * Pushes the lease of a running dispatch along, one write per tick of
   * {@link DispatchLease}. <code>LEASED_BY</code> is in the condition, so a node whose
   * lease ran out and was taken over renews nothing and learns that it lost the entry.
   */
  private static final String RENEW_LEASE = """
      UPDATE %s \
      SET LEASED_UNTIL = ?, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND LEASED_BY = ? AND STATUS = '%s'""";

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
   * <p>
   * This is also where the attempt is counted, because this is where it ended, and where
   * the lease is given back.
   * <p>
   * <code>LEASED_BY</code> is in the condition for the reason {@link #MARK_ENTRY_BLOCKED}
   * and {@link #RESCHEDULE_ENTRY} carry it: a node which lost the entry writes nothing.
   */
  private static final String MARK_ENTRY_DONE = """
      UPDATE %s \
      SET STATUS = '%s', DONE_AT = ?, DEDUP_KEY = ID, ATTEMPTS = ATTEMPTS + 1, \
      LEASED_BY = NULL, LEASED_UNTIL = NULL \
      WHERE ID = ? AND LEASED_BY = ?""";

  /**
   * Blocking releases DEDUP_KEY the way marking an entry DONE does, and for a reason
   * which is easy to miss: the key is what refuses a second schedule of the same
   * operation, so a blocked entry which kept it would silence the very repetition the
   * application needs - it would ask, the outbox would answer no, and that answer looks
   * exactly like a correct deduplication. The row stays for whoever repairs it, and the
   * new attempt of the operation is a row of its own.
   * <p>
   * The attempt which led here is counted, and the lease is given back so the row is not
   * held by a node which has nothing left to do with it.
   */
  private static final String MARK_ENTRY_BLOCKED = """
      UPDATE %s \
      SET STATUS = '%s', DEDUP_KEY = ID, ATTEMPTS = ATTEMPTS + 1, \
      LEASED_BY = NULL, LEASED_UNTIL = NULL \
      WHERE ID = ? AND LEASED_BY = ?""";

  /**
   * Says when the entry is to be read again after an attempt which did not get through,
   * counts that attempt and gives the lease back. All three in one write, because an entry
   * which is due again while still leased would be waited out to the end of the lease.
   * <p>
   * <code>LEASED_BY</code> is in the condition of this write and of the two marks above,
   * so a node whose lease was taken over writes nothing. Its dispatch runs to its end, but
   * what the entry says is the business of the node holding it now: an attempt which failed
   * here must not schedule an entry the other node has just finished, and it must not block
   * one either.
   */
  private static final String RESCHEDULE_ENTRY = """
      UPDATE %s \
      SET NEXT_ATTEMPT_AT = ?, ATTEMPTS = ATTEMPTS + 1, LEASED_BY = NULL, LEASED_UNTIL = NULL \
      WHERE ID = ? AND LEASED_BY = ?""";

  /**
   * What the housekeeping picks up: the entries which were dispatched long enough ago.
   * The bound is not part of it, because the three databases spell one in three ways -
   * {@link io.vanillabp.integration.adapter.migration.jdbc.JdbcDialect} adds it.
   */
  private static final String EXPIRED_DONE_ENTRIES_FROM = "FROM %s";

  private static final String EXPIRED_DONE_ENTRIES_WHERE = "STATUS = '%s' AND DONE_AT < ?";

  /**
   * The entries written before the column existed which still need it: they wait or they
   * are blocked, they name a payload among their arguments, and the column is empty. A
   * dispatched entry is left out on purpose - its payload went with the dispatch, so
   * nothing asks about it any more, and the history is the part of the table which is
   * large.
   */
  private static final String ENTRIES_MISSING_THEIR_PAYLOAD_REFERENCE_FROM = "FROM %s";

  private static final String ENTRIES_MISSING_THEIR_PAYLOAD_REFERENCE_WHERE = "PAYLOAD_REFERENCE IS NULL AND STATUS <> '%s' AND ARGS LIKE ?";

  /**
   * The pattern which finds an entry whose arguments name a payload. It is the argument's
   * own name, so a rename of that constant carries this statement with it.
   */
  private static final String ARGS_NAMING_A_PAYLOAD = "%"
      + PhaseTwoCall.ARG_PAYLOAD_REFERENCE
      + "%";

  private static final String WRITE_PAYLOAD_REFERENCE = "UPDATE %s SET PAYLOAD_REFERENCE = ? WHERE ID = ?";

  /**
   * How many entries one round of the backfill reads. It is the same thousand the
   * housekeeping starts a night with, and for the same reason: a bound keeps one
   * statement short on every database.
   */
  private static final int BACKFILL_PER_ROUND = 1_000;

  /**
   * How many dispatched entries the housekeeping still owes - the number a window
   * publishes when it closes. It reads the index over STATUS and DONE_AT, the one the
   * delete above reads.
   */
  private static final String COUNT_EXPIRED_DONE_ENTRIES = """
      SELECT COUNT(*) FROM %s WHERE STATUS = '%s' AND DONE_AT < ?""";

  /**
   * The column an entry names its payload in. The reference travels among the arguments
   * as well, where identifiers travel (decision 62 in the repository's DECISIONS.md), and
   * this column carries the same value in a shape an index reaches: the housekeeping asks
   * the entries whether one of them still names a payload, and inside a column of text
   * that question is a scan (decision 76).
   */
  public static final String PAYLOAD_REFERENCE_COLUMN = "PAYLOAD_REFERENCE";

  /**
   * The columns a later version of VanillaBP added to this table. A table created by an
   * earlier version has neither, and every poll would fail on a column which is not there -
   * so this is said at startup, with the statement which repairs it, instead of at the first
   * dispatch.
   */
  private static final List<AddedColumn> ADDED_COLUMNS = List
      .of(
          new AddedColumn(
              "LEASED_BY", "VARCHAR(255) (nullable: an entry nobody is dispatching is leased by nobody)", "an entry cannot be claimed, so nothing is dispatched at all"),
          new AddedColumn(
              "LEASED_UNTIL", "TIMESTAMP (the type your database uses for the existing column NEXT_ATTEMPT_AT), nullable", "an entry cannot be claimed, so nothing is dispatched at all"),
          new AddedColumn(
              PAYLOAD_REFERENCE_COLUMN, "VARCHAR(36) (nullable: an entry which carries no payload names none)", "the housekeeping cannot tell which payloads are still needed, so it removes none of them and the payload table grows"));

  /**
   * A column this version of VanillaBP reads which an older table does not have.
   *
   * @param name The column
   * @param definition What to add it as, in the words of whoever writes the statement
   * @param whatIsLost What does not work while it is missing
   */
  private record AddedColumn(String name, String definition, String whatIsLost) {
  }

  /**
   * The indexes this table is read by. One list, because the DDL which creates them and the
   * startup check which asks an existing table for them read it, and a second list would sooner
   * or later name something the other does not. Every one of them filters STATUS and then reads a
   * moment, in that order, and every moment gets an index of its own: one index over two of them
   * would serve neither question.
   */
  private static final List<TableIndex> INDEXES = List
      .of(
          new TableIndex("_DUE", "STATUS, NEXT_ATTEMPT_AT"),
          new TableIndex("_AGE", "STATUS, DONE_AT"),
          new TableIndex("_OLDEST", "STATUS, CREATED_AT"),
          new TableIndex("_PAYLOAD_REF", PAYLOAD_REFERENCE_COLUMN));

  /**
   * One index of the outbox table, named after that table so two outboxes on one schema keep
   * their indexes apart the way they keep their tables apart.
   *
   * @param suffix What is appended to the table name to name the index
   * @param columns The columns it spans, in the order the statements read them
   */
  private record TableIndex(String suffix, String columns) {

    /**
     * @param tableName The table this outbox writes into
     * @return The name the index carries on that table
     */
    private String nameOn(
        final String tableName) {

      return tableName + suffix;

    }

    /**
     * @param tableName The table this outbox writes into
     * @return The statement which creates the index on that table
     */
    private String createOn(
        final String tableName) {

      return "CREATE INDEX %s ON %s (%s)".formatted(nameOn(tableName), tableName, columns);

    }

  }

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

  private final String renewLease;

  private final String selectClaimedEntry;

  private final String markEntryDone;

  private final String markEntryBlocked;

  private final String rescheduleEntry;

  private final String countExpiredDoneEntries;

  /**
   * Where this node says that it is house-keeping this store tonight, so no other node
   * measures its work at the same time.
   */
  private final JdbcHousekeepingLease housekeepingLease;

  /**
   * What removes the dispatched entries and the orphaned payloads, and when.
   */
  private final OutboxHousekeeping housekeeping;

  /**
   * The bounded delete of the dispatched entries, built on the first housekeeping run.
   * It needs the database product, which is read from a connection, so it cannot be built
   * in the constructor next to the other statements.
   */
  private String deleteExpiredDoneEntries;

  /**
   * How many entries {@link #deleteExpiredDoneEntries} was built for. The bound is part
   * of the statement on every database, so a batch of another size builds it again.
   */
  private int deleteExpiredDoneEntriesBoundedAt;

  /**
   * What the two fields above are guarded by. A lock of its own and not this dispatcher:
   * the poll holds the dispatcher's monitor while it hands entries to the lanes, and it
   * waits there when a lane is full - the housekeeping runs on a thread of its own and
   * must not queue behind that.
   */
  private final Object deleteExpiredDoneEntriesLock = new Object();

  private final DueEntryPoller poller;

  private final DispatchLanes lanes;

  /**
   * What a running dispatch holds its entry with, and what keeps that hold alive while it
   * runs.
   */
  private final DispatchLease lease;

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
   * @param attempts How many dispatch attempts of it have ENDED
   * @param createdAt When the entry was written - a replacing call sets it anew,
   *          because the row then carries a younger operation
   * @param heldBefore Who held this entry before this poll read it, or <code>null</code>
   *          where nobody did. It is the second half of "somebody has had this entry
   *          already": an attempt which ended is counted, and an attempt which a node died
   *          in the middle of left its name here and nothing else
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
                       Instant createdAt,
                       String heldBefore) {

    /**
     * Whether a dispatch has had this entry before, which is what makes a START probe the
     * BPMS it was meant for instead of starting a second workflow.
     *
     * @return Whether an attempt ended or a holder disappeared
     */
    boolean wasTakenBefore() {

      return (attempts > 0) || (heldBefore != null);

    }

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
   * Builds the dispatcher of one JDBC outbox: the statements for the table it was given,
   * the poller, the lanes and the renewal of the leases. None of the three does anything
   * before {@link #start()}, and the table is not touched before
   * {@link #prepareSchema()}.
   *
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
    this.claimEntry = CLAIM_ENTRY.formatted(tableName, STATUS_OPEN);
    this.renewLease = RENEW_LEASE.formatted(tableName, STATUS_OPEN);
    this.selectClaimedEntry = SELECT_CLAIMED_ENTRY.formatted(tableName);
    this.markEntryDone = MARK_ENTRY_DONE.formatted(tableName, STATUS_DONE);
    this.markEntryBlocked = MARK_ENTRY_BLOCKED.formatted(tableName, STATUS_BLOCKED);
    this.rescheduleEntry = RESCHEDULE_ENTRY.formatted(tableName);
    this.countExpiredDoneEntries = COUNT_EXPIRED_DONE_ENTRIES.formatted(tableName, STATUS_DONE);
    this.housekeepingLease = new JdbcHousekeepingLease(
        connections, properties
            .getJdbc()
            .housekeepingTableName());
    this.housekeeping = new OutboxHousekeeping(this, properties, metrics);
    this.poller = new DueEntryPoller(
        "vanillabp-outbox", properties.getPollInterval(), this::poll, this::earliestDueAt);
    this.lanes = new DispatchLanes("vanillabp-outbox-dispatch", properties.getDispatchThreads());
    this.lease = new DispatchLease("vanillabp-outbox-lease", properties.getAttemptFrequency());

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
      housekeepingLease.createSchemaIfNotExists();
    } else {
      // the application creates its schema itself - a missing table is then a
      // deployment which forgot to apply the migration, and it is said at startup instead of at
      // the first workflow start
      validateTableExists();
      payloadStore.validateSchemaExists();
      housekeepingLease.validateSchemaExists();
    }
    fillPayloadReferencesOfWaitingEntries();

  }

  /**
   * Starts polling. The platform calls this once the BPMN resources are deployed and
   * workflow processing has started, so nothing recovered is carried to a BPMS which has
   * not seen the models yet.
   */
  public void start() {

    poller.start();
    housekeeping.start();

  }

  /**
   * Stops the poller, the lanes and the renewal of the leases. What a lane was still
   * holding stays OPEN in the table and keeps its lease until it runs out, so the next
   * start of this node or a poll of another one takes it then.
   */
  public void stop() {

    poller.stop();
    lanes.stop();
    lease.stop();
    housekeeping.stop();

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
        validateColumns(connection);
        reportMissingIndexes(connection);
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(buildCreateTable(connection, tableName));
        for (final var index : INDEXES) {
          statement.executeUpdate(index.createOn(tableName));
        }
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
   * Verifies that the outbox table exists AND carries the columns this version writes, for
   * an application creating its schema itself. The message names the table, the property
   * which would have created it and the artifact carrying the statements.
   *
   * @throws IllegalStateException If the table or one of its columns is missing
   */
  private void validateTableExists() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        validateColumns(connection);
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
   * Verifies the columns which a later version of VanillaBP added to this table. A failure and
   * not a warning, unlike a missing index: an entry cannot be claimed without them, so an
   * application which booted anyway would dispatch nothing and say nothing either.
   *
   * @param connection The connection to the database holding the table
   * @throws IllegalStateException If a column is missing
   */
  private void validateColumns(
      final Connection connection) throws SQLException {

    for (final var column : ADDED_COLUMNS) {
      if (JdbcSchema.columnExists(connection, tableName, column.name())) {
        continue;
      }
      throw new IllegalStateException(
          """
              The phase-two outbox table '%s' has no column '%s'! It was added to the table of \
              VanillaBP after your database was created, and without it %s. Either
              - apply the current schema of VanillaBP with your migration tool: the artifact \
              'io.vanillabp:vanillabp-schema' ships the Liquibase changelog \
              'vanillabp/schema/changelog.xml' and the SQL generated from it for Flyway, or
              - add the column yourself: ALTER TABLE %s ADD %s %s."""
              .formatted(tableName, column.name(), column.whatIsLost(), tableName, column.name(), column
                  .definition()));
    }

  }

  /**
   * Names the indexes this table needs and does not have, with the statement which adds each of
   * them. A table created by an earlier version of VanillaBP is missing whichever of them that
   * version did not know, and the question behind it is then a sequential scan growing with
   * everything the table ever held - which is a cost nobody sees until the table is large. A
   * warning and not a failure: the application runs correctly without them, and creating an index
   * on a large table is a decision with a lock on it, not something a boot should do behind its
   * operator's back.
   *
   * @param connection The connection to the database holding the table
   */
  private void reportMissingIndexes(
      final Connection connection) {

    final var missing = new ArrayList<String>();
    for (final var index : INDEXES) {
      if (!JdbcSchema.indexExists(connection, tableName, index.nameOn(tableName))) {
        missing.add(index.createOn(tableName));
      }
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
        PAYLOAD_REFERENCE VARCHAR(36), \
        IDEMPOTENCY_KEY VARCHAR(512), \
        DEDUP_KEY VARCHAR(512) NOT NULL UNIQUE, \
        STATUS VARCHAR(16) NOT NULL, \
        CREATED_AT %s NOT NULL, \
        ATTEMPTS INT NOT NULL, \
        NEXT_ATTEMPT_AT %s NOT NULL, \
        DONE_AT %s, \
        LEASED_BY VARCHAR(255), \
        LEASED_UNTIL %s)"""
        .formatted(
            tableName,
            timestampType,
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
   * A lane whose queue is full makes this thread wait. Waiting is the back pressure of a
   * backlog which arrives faster than it leaves, and it is what keeps the backlog in the
   * table, where it can be read. Every step of this thread borrows its connection for the
   * moment it needs it, so the waiting holds none: a poller keeping one would take it from
   * the lanes, which need a connection for every write of a dispatch.
   */
  private synchronized void poll() {

    try {
      for (final var entry : dueEntries()) {
        handOverToItsLane(entry);
      }
    } catch (final Exception e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Claims the entry and hands it to the lane of its aggregate, with the renewal of its
   * lease already running.
   * <p>
   * The renewal starts here and not where the lane picks the entry up, because between the
   * two the entry waits in the lane's queue. A wait longer than the lease would let another
   * node claim an entry this one is about to dispatch, and both would carry the operation
   * out. The queue is short, so the wait is short as well, but short is not the same as
   * impossible and nothing about the queue length is promised to anybody.
   * <p>
   * Where no lane takes the entry - this node is stopping - the renewal ends here too, and
   * the log says which operation waits for its lease to run out.
   *
   * @param entry The due entry, as the select read it
   */
  private void handOverToItsLane(
      final Entry entry) throws SQLException {

    final Entry claimed;
    Connection connection = null;
    try {
      connection = connections.acquire();
      claimed = claim(connection, entry);
    } finally {
      release(connection);
    }
    if (claimed == null) {
      return;
    }
    final var held = lease.renewWhile(claimed.id(), this::renewLease);
    final boolean taken;
    try {
      taken = lanes.runInOrderOf(claimed.orderingKey(), () -> dispatch(claimed, held));
    } catch (final RuntimeException | Error e) {
      // nothing will dispatch this entry, so nothing would close the renewal either
      held.close();
      throw e;
    }
    if (!taken) {
      // the same as above, for the one case which is no failure: this node is stopping and
      // the lanes take nothing more. The renewal is closed here rather than by the order
      // stop() happens to have, which is an agreement between two calls and holds nothing
      held.close();
      reportTheEntryNoLaneTook(claimed);
    }

  }

  /**
   * Says that an entry this poll claimed will not be dispatched, because the lanes take
   * nothing any more.
   * <p>
   * Nothing is lost. The entry stays OPEN and keeps its lease until it runs out, and the
   * next poll of this node or of another one takes it then. What it costs is that wait, one
   * <code>vanillabp.outbox.attempt-frequency</code>, and this line is what says so to
   * whoever reads the log of a shutdown.
   *
   * @param entry The claimed entry no lane took
   */
  private void reportTheEntryNoLaneTook(
      final Entry entry) {

    log
        .info(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' was not "
                + "handed to a dispatch thread because this node is stopping - the outbox entry '{}' stays "
                + "open and is dispatched once its lease runs out",
            entry.operation(),
            entry.bpmnProcessId(),
            entry.workflowModuleId(),
            entry.aggregateId(),
            entry.id());

  }

  private List<Entry> dueEntries() throws SQLException {

    Connection connection = null;
    try {
      connection = connections.acquire();
      return loadDueEntries(connection);
    } finally {
      release(connection);
    }

  }

  /**
   * Writes the payload reference of the entries which were planned before the column
   * existed.
   * <p>
   * The reference has always travelled among the arguments, and it travels there still;
   * the column is what the housekeeping asks. An entry written by an earlier version
   * therefore names its payload where the dispatch reads it and nowhere the housekeeping
   * looks, and that payload would be removed as an orphan - taking the bytes away from a
   * dispatch which is still to come.
   * <p>
   * <strong>What this costs on a large table.</strong> Only the entries which still WAIT
   * are read: a dispatched entry gave its payload back at the dispatch, so nothing asks
   * about it any more. Those are the entries the outbox owes something to plus the ones
   * somebody has to repair, which is a small number on any application whose outbox is
   * healthy and a known number on one whose outbox is not. The history, which is the part
   * that grows, is never touched. The work is done in rounds of
   * {@link #BACKFILL_PER_ROUND} rows, so no statement holds a lock on more than that, and
   * it runs before the poller starts, because an entry dispatched meanwhile would leave a
   * payload behind.
   * <p>
   * It costs one read per start once the column is filled, and that read is answered by
   * the index over the column.
   */
  private void fillPayloadReferencesOfWaitingEntries() {

    var filled = 0;
    try {
      while (true) {
        final var round = fillOneRoundOfPayloadReferences();
        if (round == 0) {
          break;
        }
        filled += round;
      }
    } catch (final SQLException e) {
      // the entries which were not reached keep their payload reference among their
      // arguments, so their dispatch works; what is at risk is the payload of one of them
      // being removed as an orphan, and that is worth a line somebody can act on
      log
          .warn(
              "Could not write the payload reference of the entries of table '{}' which were planned before "
                  + "the column existed - the housekeeping may remove the payload of such an entry. Run the "
                  + "migration of 'io.vanillabp:vanillabp-schema' or restart the application",
              tableName,
              e);
    }
    if (filled > 0) {
      log
          .info(
              "Wrote the payload reference of {} entrie(s) of table '{}' which were planned before the column "
                  + "existed",
              filled,
              tableName);
    }

  }

  /**
   * One round of the backfill.
   *
   * @return How many entries were written, zero where there is nothing left to do
   */
  private int fillOneRoundOfPayloadReferences() throws SQLException {

    Connection connection = null;
    try {
      connection = connections.acquire();
      final var references = new LinkedHashMap<String, String>();
      final var select = JdbcDialect
          .of(connection)
          .selectAtMost(
              "ID, ARGS",
              ENTRIES_MISSING_THEIR_PAYLOAD_REFERENCE_FROM.formatted(tableName),
              ENTRIES_MISSING_THEIR_PAYLOAD_REFERENCE_WHERE.formatted(STATUS_DONE),
              BACKFILL_PER_ROUND);
      try (var statement = connection.prepareStatement(select)) {
        statement.setString(1, ARGS_NAMING_A_PAYLOAD);
        try (var resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            final var reference = PhaseTwoCall
                .deserializeArgs(resultSet.getString(2))
                .get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
            if (reference != null) {
              references.put(resultSet.getString(1), reference);
            }
          }
        }
      }
      if (references.isEmpty()) {
        // either nothing is left or what is left names no payload after all, and both
        // mean the same here: another round would read the same rows again
        return 0;
      }
      try (var statement = connection.prepareStatement(WRITE_PAYLOAD_REFERENCE.formatted(tableName))) {
        for (final var entry : references.entrySet()) {
          statement.setString(1, entry.getValue());
          statement.setString(2, entry.getKey());
          statement.addBatch();
        }
        statement.executeBatch();
      }
      return references.size();
    } finally {
      release(connection);
    }

  }

  @Override
  public String storeName() {

    return storeName;

  }

  @Override
  public boolean claimHousekeepingUntil(
      final String owner,
      final Instant until) {

    return housekeepingLease.claimUntil(storeName
        + "@"
        + tableName, owner, until);

  }

  @Override
  public void releaseHousekeeping(
      final String owner) {

    housekeepingLease.release(storeName
        + "@"
        + tableName, owner);

  }

  /**
   * {@inheritDoc}
   * <p>
   * One statement, on a connection borrowed for it and given back, the way every other
   * step of this dispatcher borrows one. The bound is inside the statement because
   * <code>DELETE ... LIMIT</code> is no portable SQL.
   */
  @Override
  public int removeDispatchedEntriesOlderThan(
      final Instant threshold,
      final int maxRows) {

    if (maxRows < 1) {
      return 0;
    }
    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(deleteExpiredDoneEntries(connection, maxRows))) {
        statement.setTimestamp(1, Timestamp.from(threshold));
        return statement.executeUpdate();
      }
    } catch (final SQLException e) {
      log.warn("Could not remove the dispatched entries of the outbox table '{}'", tableName, e);
      return 0;
    } finally {
      release(connection);
    }

  }

  /**
   * {@inheritDoc}
   * <p>
   * The entries were removed first, so the payloads they named are named by nothing now
   * and go with this call. What an entry still names - an entry which waits, and an entry
   * which is blocked until somebody repairs it - is not removed by age at all, which the
   * payload store asks its own entries about.
   */
  @Override
  public int removeOrphanedPayloadsOlderThan(
      final Instant threshold,
      final int maxRows) {

    return payloadStore.removeOrphansOlderThan(threshold, maxRows);

  }

  @Override
  public OptionalLong countDispatchedEntriesOlderThan(
      final Instant threshold) {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(countExpiredDoneEntries)) {
        statement.setTimestamp(1, Timestamp.from(threshold));
        try (var resultSet = statement.executeQuery()) {
          return resultSet.next() ? OptionalLong.of(resultSet.getLong(1)) : OptionalLong.empty();
        }
      }
    } catch (final SQLException e) {
      // a number which could not be read stays a gap in the meter rather than a zero
      log.debug("Could not count the dispatched entries of the outbox table '{}'", tableName, e);
      return OptionalLong.empty();
    } finally {
      release(connection);
    }

  }

  /**
   * The delete of at most so many dispatched entries, built once per ceiling and kept
   * afterwards.
   *
   * @param connection The connection, read for the database product
   * @param maxRows The most rows the statement may remove
   * @return The statement to run
   */
  private String deleteExpiredDoneEntries(
      final Connection connection,
      final int maxRows) throws SQLException {

    synchronized (deleteExpiredDoneEntriesLock) {
      if ((deleteExpiredDoneEntries != null) && (deleteExpiredDoneEntriesBoundedAt == maxRows)) {
        return deleteExpiredDoneEntries;
      }
      final var dialect = JdbcDialect.of(connection);
      final var expired = dialect
          .selectAtMost(
              "ID",
              EXPIRED_DONE_ENTRIES_FROM.formatted(tableName),
              EXPIRED_DONE_ENTRIES_WHERE.formatted(STATUS_DONE),
              maxRows);
      deleteExpiredDoneEntries = dialect.deleteWhatWasPicked(tableName, "ID", expired);
      deleteExpiredDoneEntriesBoundedAt = maxRows;
      return deleteExpiredDoneEntries;
    }

  }

  private List<Entry> loadDueEntries(
      final Connection connection) throws SQLException {

    final var now = Timestamp.from(Instant.now());
    final var entries = new ArrayList<Entry>();
    try (var statement = connection.prepareStatement(selectDueEntries)) {
      statement.setTimestamp(1, now);
      statement.setInt(2, properties.getBlockAfterAttempts());
      statement.setTimestamp(3, now);
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          entries.add(new Entry(
              resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet
                  .getString(5), resultSet.getString(6), resultSet.getString(7), resultSet.getInt(8), writtenAt(
                      resultSet, 9), resultSet.getString(10)));
        }
      }
    }
    return entries;

  }

  /**
   * Claims an entry using an optimistic update: writing this node's name and the end of
   * the lease makes concurrent pollers (or other instances) skip the entry, and the claimed
   * entry renews the lease rather than losing it ({@link DispatchLease}).
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

    final var leaseEnds = lease.endsAt();
    try (var statement = connection.prepareStatement(claimEntry)) {
      // the claim leases the entry for one attempt-frequency and the renewal pushes that
      // moment along until the dispatch is over, which is what keeps other pollers off it
      // for as long as this node has something to do with it. The growing
      // backoff belongs to a FAILED dispatch and is written there, so a node which dies
      // mid-dispatch does not leave behind the long distance of an attempt nobody made
      statement.setString(1, lease.owner());
      statement.setTimestamp(2, Timestamp.from(leaseEnds));
      statement.setTimestamp(3, Timestamp.from(leaseEnds));
      statement.setString(4, entry.id());
      statement.setTimestamp(5, Timestamp.from(Instant.now()));
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
                        resultSet, 7),
                // who held the row BEFORE this claim, which the claim has just overwritten
                // with this node - so it travels from the row the select read
                entry.heldBefore())
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
   * that long would make the pool the limit of how many entries may travel at once. The
   * lease is renewed the same way, one short write per tick on a connection borrowed and
   * given back, so holding an entry costs no connection either.
   * <p>
   * The renewal ends here, before the write which says how the attempt ended. That write
   * needs a lease which has not run out, which the last renewal gave it, and not one which
   * is still growing - and a renewal outliving the write would find the entry no longer
   * OPEN and report a lease it never lost.
   *
   * @param entry The claimed entry
   * @param held The renewal which started with the claim, closed when this attempt is over
   */
  private void dispatch(
      final Entry entry,
      final DispatchLease.Held held) {

    final String payloadReference;
    // everything the attempt does is inside, so the renewal is let go whichever way the
    // attempt ends - reading the arguments of the entry included
    try (held) {
      final var args = PhaseTwoCall.deserializeArgs(entry.serializedArgs());
      payloadReference = args.get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
      // an entry which was taken before is one whose dispatch may have reached the BPMS
      // already (recovered/retried): the router then runs the
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
          entry.wasTakenBefore(),
          entry.createdAt());
    } catch (final Exception e) {
      reportFailedDispatch(entry, e);
      return;
    }
    if (!markDone(entry)) {
      // the mark did not take, and either way the bytes stay. The entry may belong to
      // another node, which is still dispatching and would find a payload which is gone; or
      // the database could not be asked, and then the entry is still OPEN here and wants
      // those bytes for the attempt which follows its lease
      return;
    }
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
      if (!markBlocked(entry)) {
        return;
      }
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
      if (!markBlocked(entry)) {
        return;
      }
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
      if (!rescheduleAt(entry, Instant.now().plus(retryAfter))) {
        return;
      }
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
    // attempts() is the count of the attempts which ended before this one, so
    // attemptDelay(0) is the distance after the first failure: close, because most
    // failures are momentary
    final var retryIn = properties.attemptDelay(entry.attempts());
    if (!rescheduleAt(entry, Instant.now().plus(retryIn))) {
      return;
    }
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
   * @return Whether the entry says it is done now, which it does where this node still held
   *         it and the database wrote it down
   */
  private boolean markDone(
      final Entry entry) {

    final var doneAt = Instant.now();
    final var outcome = update(markEntryDone, statement -> {
      statement.setTimestamp(1, Timestamp.from(doneAt));
      statement.setString(2, entry.id());
      statement.setString(3, lease.owner());
    });
    if (!theEntryNowSays(entry, outcome, "that it is done")) {
      return false;
    }
    poller.somethingIsDueAt(doneAt.plus(properties.getRetention()));
    return true;

  }

  /**
   * Takes the entry out of what any poll reads. Nothing in VanillaBP moves it back.
   *
   * @param entry The entry nobody is to try again
   * @return Whether the entry says it is blocked now, which it does where this node still
   *         held it and the database wrote it down
   */
  private boolean markBlocked(
      final Entry entry) {

    final var outcome = update(markEntryBlocked, statement -> {
      statement.setString(1, entry.id());
      statement.setString(2, lease.owner());
    });
    return theEntryNowSays(entry, outcome, "that it is blocked");

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
   * @return Whether the entry says its new due time, which it does where this node still
   *         held it and the database wrote it down
   */
  private boolean rescheduleAt(
      final Entry entry,
      final Instant nextAttempt) {

    final var outcome = update(rescheduleEntry, statement -> {
      statement.setTimestamp(1, Timestamp.from(nextAttempt));
      statement.setString(2, entry.id());
      statement.setString(3, lease.owner());
    });
    if (!theEntryNowSays(entry, outcome, "when it is due again")) {
      return false;
    }
    poller.somethingIsDueAt(nextAttempt);
    return true;

  }

  /**
   * Whether the entry carries what a write of this dispatcher wanted to put on it, and the
   * line an operator reads where it does not.
   * <p>
   * The two ways a write does not take are told apart here, because only one of them is the
   * entry having changed hands. A write the database could not run leaves the entry with
   * this node, exactly as it was, so the caller must go on as if nothing had been written -
   * which is what it is: the next poll reads the entry again once its lease runs out.
   *
   * @param entry The entry the write was about
   * @param outcome What became of the write
   * @param whatTheWriteSaid What the entry would say now, in the words of the report
   * @return Whether the entry says it
   */
  private boolean theEntryNowSays(
      final Entry entry,
      final WriteOutcome outcome,
      final String whatTheWriteSaid) {

    return switch (outcome) {
      case WRITTEN -> true;
      case NO_ROW_MATCHED -> {
        reportResultOfALostEntry(entry);
        yield false;
      }
      case DATABASE_COULD_NOT_BE_ASKED -> {
        reportTheWriteWhichDidNotGetThrough(entry, whatTheWriteSaid);
        yield false;
      }
    };

  }

  /**
   * Says that an attempt ended and that the database could not be asked to write down how.
   * <p>
   * The entry keeps its status, its due time and its lease, so the next poll of this node or
   * of another one reads it once that lease runs out. Where the attempt had reached the BPMS
   * the operation then runs a second time, which is the at-least-once this outbox documents.
   * What the entry must not lose meanwhile is its payload: the dispatch it is waiting for
   * needs those bytes.
   *
   * @param entry The entry whose write was not run
   * @param whatTheWriteSaid What the entry would say now, had the write got through
   */
  private void reportTheWriteWhichDidNotGetThrough(
      final Entry entry,
      final String whatTheWriteSaid) {

    log
        .warn(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' ended, but "
                + "the database could not be asked to write {} - the outbox entry '{}' stays as it is and "
                + "is dispatched again once its lease runs out",
            entry.operation(),
            entry.bpmnProcessId(),
            entry.workflowModuleId(),
            entry.aggregateId(),
            whatTheWriteSaid,
            entry.id());

  }

  /**
   * Says that an attempt ended on an entry this node does not hold any more, so what it
   * wanted to write was dropped.
   * <p>
   * The renewal said the same thing earlier, at the moment the entry changed hands. This
   * message is the other end of it and is worth its own line: it names the operation which
   * ran twice, and it is the proof that the second run did not overwrite what the node
   * holding the entry wrote.
   *
   * @param entry The entry which was taken over while this node dispatched it
   */
  private void reportResultOfALostEntry(
      final Entry entry) {

    log
        .warn(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' ended, but "
                + "the outbox entry '{}' belongs to another node by now - the result of this dispatch was "
                + "dropped and the entry says what that node wrote",
            entry.operation(),
            entry.bpmnProcessId(),
            entry.workflowModuleId(),
            entry.aggregateId(),
            entry.id());

  }

  /**
   * Pushes the lease of an entry this node is dispatching along, in a transaction of its
   * own: one write, on a connection borrowed and given back, once per tick of
   * {@link DispatchLease}.
   *
   * @param entryId The entry being dispatched
   * @param leaseEnd How long the lease is to last now
   * @return Whether this node still holds the entry. A write which matched no row says it
   *         does not any more; a write which could not be asked at all says nothing, so the
   *         renewal keeps trying rather than giving the entry up over one hiccup
   */
  private boolean renewLease(
      final String entryId,
      final Instant leaseEnd) {

    return update(renewLease, statement -> {
      statement.setTimestamp(1, Timestamp.from(leaseEnd));
      statement.setTimestamp(2, Timestamp.from(leaseEnd));
      statement.setString(3, entryId);
      statement.setString(4, lease.owner());
    }) != WriteOutcome.NO_ROW_MATCHED;

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
   * @return What became of the write
   */
  private WriteOutcome update(
      final String sql,
      final StatementArguments arguments) {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(sql)) {
        arguments.setOn(statement);
        return statement.executeUpdate() == 0
            ? WriteOutcome.NO_ROW_MATCHED
            : WriteOutcome.WRITTEN;
      }
    } catch (final SQLException e) {
      log.error("Could not update an entry of the phase-two outbox table '{}'", tableName, e);
      return WriteOutcome.DATABASE_COULD_NOT_BE_ASKED;
    } finally {
      release(connection);
    }

  }

  /**
   * What became of one write of this dispatcher. Three answers and not a number, because a
   * write which matched no row and a write nobody could run mean opposite things for the
   * entry: the first says somebody else owns it now, the second says the entry is exactly as
   * it was and this node still holds it.
   */
  private enum WriteOutcome {

    /**
     * The row was changed, so this node held the entry and what the write says stands.
     */
    WRITTEN,

    /**
     * The statement ran and matched no row. Every write of an ended attempt names the holder
     * of the lease, so this is the entry having changed hands while the dispatch ran.
     */
    NO_ROW_MATCHED,

    /**
     * The database could not be asked at all. Nothing was written, so the entry keeps the
     * status, the due time and the lease it had before.
     */
    DATABASE_COULD_NOT_BE_ASKED

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
