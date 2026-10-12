package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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
 * Dispatches the entries of the JDBC phase-two outbox which are committed but not yet
 * processed (see {@link JdbcPhaseTwoOutboxStore}). It hands them to the core's
 * {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit ({@link JdbcPhaseTwoOutboxStore} triggers that), and</li>
 * <li>through a poller, for crash recovery and retries. The platform starts it once its
 * deployment is done. The poller does not poll on a fixed rhythm. It sleeps until the
 * earliest entry for which this store still owes something is due. For work which another
 * node wrote down before it died, <code>vanillabp.outbox.poll-interval</code> limits the
 * sleep (see {@link DueEntryPoller}).</li>
 * </ul>
 * The poller uses a plain scheduled executor. So no framework scheduler is registered or
 * used, and an application's own scheduling setup stays as it is. Due entries (status
 * {@link #STATUS_OPEN}) are claimed atomically. That is an optimistic update which writes
 * <code>LEASED_BY</code> and <code>LEASED_UNTIL</code>, and the lease lasts one
 * <code>vanillabp.outbox.attempt-frequency</code>. So several instances do not dispatch the
 * same entry at the same time. <strong>A claimed entry renews its lease</strong>
 * ({@link DispatchLease}), from the claim until its dispatch is over. An entry may wait for
 * its lane, or travel longer than one lease. It still stays the claim of the node which
 * carries it, and the next poll does not take it. A dispatch which FAILS writes the next
 * attempt itself, at the growing distance of {@link PhaseTwoOutboxProperties#attemptDelay(int)}.
 * The distance doubles with each attempt up to <code>vanillabp.outbox.max-attempt-frequency</code>.
 * So an outage of hours drains by itself when the BPMS comes back. After a successful
 * dispatch the entry is marked {@link #STATUS_DONE}. It stays in the table so that support
 * can read it. It is deleted later, once <code>vanillabp.outbox.retention</code> has
 * passed. After <code>vanillabp.outbox.block-after-attempts</code> failed attempts an entry
 * is marked {@link #STATUS_BLOCKED} and waits for a person, see
 * {@link PhaseTwoOutboxProperties#BLOCKED_ENTRIES_GUIDE}.
 * <p>
 * <strong>An entry says why its last attempt did not get through.</strong> Every write
 * which ends an attempt sets <code>LAST_FAILURE</code>. It holds the reason where the
 * attempt failed or got "not yet" as the answer, and nothing where it went through (see
 * {@link LastFailure}). So a blocked entry names its reason in the table, and so does an
 * entry which is still being repeated.
 * <p>
 * <strong>Waiting for a read model is not an attempt.</strong> The adapter may answer a
 * dispatch with {@link PhaseTwoRetryLater}. Then the entry is written back with the window
 * the adapter named, and <code>ATTEMPTS</code> stays as it was. Time ends that wait. Once
 * <code>vanillabp.outbox.wait-for-visibility-at-most</code> has passed since the entry was
 * written, the entry is blocked (see
 * {@link PhaseTwoOutboxProperties#hasWaitedForVisibilityLongEnough} and decision 113 in the
 * repository's DECISIONS.md).
 * <p>
 * <strong><code>ATTEMPTS</code> counts attempts, not claims.</strong> The column is written
 * when an attempt ENDED, together with the mark which says how it ended. So a dispatch
 * which takes its time uses up no attempts. And <code>block-after-attempts</code> blocks an
 * entry which keeps failing, not one which is slow.
 * <p>
 * <strong>Several entries leave at the same time</strong>, and the workflow aggregate
 * decides which of them may. The poller claims the due entries in the order they were
 * written. It hands each of them to the lane of its aggregate ({@link DispatchLanes}). So
 * entries of one aggregate keep their order, while entries of different aggregates run at
 * the same time. An entry which FAILED is not kept in order. It waits for its backoff, and
 * the next entry of the same aggregate passes it in the meantime. On a single thread a
 * failed entry is put back as well, so nothing changes there.
 * <p>
 * <strong>Cluster safety:</strong> several application instances (pods) may poll at the
 * same time without any distributed lock. The SELECT may return the same due entries on
 * several instances. But the claim is an optimistic UPDATE
 * (<code>WHERE ID = ? AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL &lt;= ?)</code>). Exactly
 * one instance wins the claim and dispatches the entry, the others just skip it. A node
 * which dies while it dispatches stops renewing. Its lease runs out, and the next poll of
 * any node takes the entry over. The retention cleanup is a plain DELETE which can be
 * repeated safely.
 * <p>
 * A node which is still alive may lose its entry that way. Then its dispatch runs to its
 * end, and the result is dropped. The writes which say how an attempt ended have
 * <code>LEASED_BY</code> in their condition. So only the node which holds the entry writes
 * them. Both nodes say in their log what happened. The second delivery is the remaining
 * at-least-once case which this outbox documents.
 * <p>
 * A write which the database could not run at all is the third answer, and it is kept
 * apart from both of the above. Nothing was written, so the entry keeps its status, its due
 * time and its lease. The next poll reads it once that lease runs out. So it also keeps its
 * payload, whatever the attempt did. An entry which will be dispatched again needs those
 * bytes.
 * <p>
 * The outbox table, and the table with the payloads of the calls which have one, are
 * created at startup. The exception is <code>vanillabp.outbox.create-schema</code> disabled
 * for a schema which is managed by hand. Then also create the unique constraint on
 * <code>DEDUP_KEY</code> yourself. It is the deduplication of the outbox contract in the
 * storage, and it spans the entries which still wait for their dispatch (see
 * {@link JdbcPhaseTwoOutboxStore}). The DDL stays portable. Whether the table exists is
 * checked through the JDBC metadata, because Oracle and SQL Server do not support
 * <code>CREATE TABLE IF NOT EXISTS</code>. The timestamp type is chosen per database. The
 * <code>TIMESTAMP</code> of SQL Server is a row version, and the one of MySQL has odd
 * automatic initialization and ends in 2038. The idempotency key is limited to 512
 * characters, so the key-length limit of a unique index in MySQL (3072 bytes with utf8mb4)
 * is respected.
 */
@Slf4j
public class JdbcPhaseTwoOutboxDispatcher implements OutboxHousekeeping.Store {

  /**
   * What the <code>STATUS</code> column holds while an entry still has to be dispatched.
   * Every entry is written in this state, and a poll reads only this one. An entry which
   * somebody is dispatching right now is OPEN as well. Its lease keeps it apart.
   * <p>
   * The three values are constants, because they are written into SQL here, read by the
   * store next to this class, and looked up by their name in the tests. A test which wrote
   * <code>'OPEN'</code> itself would keep passing after a rename.
   */
  public static final String STATUS_OPEN = "OPEN";

  /**
   * What the <code>STATUS</code> column holds after the operation reached the BPMS. The row
   * stays for <code>vanillabp.outbox.retention</code>, so that support can still read what
   * was dispatched. The housekeeping deletes it afterwards.
   */
  public static final String STATUS_DONE = "DONE";

  /**
   * What the <code>STATUS</code> column holds after <code>block-after-attempts</code> failed
   * attempts, or after the entry waited longer than <code>wait-for-visibility-at-most</code>
   * for its BPMS. No poll reads the entry any more, and it waits for a person. Nothing in
   * VanillaBP moves it back, and that is the point. An entry which failed that often is
   * broken, not unlucky.
   */
  public static final String STATUS_BLOCKED = "BLOCKED";

  /**
   * The due entries, oldest first. The lanes turn this order into the order of one
   * aggregate. A lane runs what it gets in the order it gets it. So the entries of one
   * aggregate have to reach it in the order they were written.
   * <p>
   * An entry which somebody is dispatching right now is left out because of its lease. Its
   * <code>NEXT_ATTEMPT_AT</code> says the same, because a claim and every renewal write
   * both. But the lease decides. That keeps the rule in one place when a due time is
   * written from somewhere else.
   */
  private static final String SELECT_DUE_ENTRIES = """
      SELECT ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, ATTEMPTS, \
      CREATED_AT, LEASED_BY \
      FROM %s \
      WHERE STATUS = '%s' AND NEXT_ATTEMPT_AT <= ? AND ATTEMPTS < ? \
      AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?) \
      ORDER BY CREATED_AT""";

  /**
   * When the earliest entry which waits for its dispatch wants to be looked at. It is the
   * select above without its time limits. That keeps the two in step. An entry which this
   * does not see would not be picked up by that one either. A BLOCKED entry is in neither,
   * because it waits for a person and not for a clock.
   * <p>
   * An entry which is being dispatched right now answers with the end of its lease, not with
   * "due". The claim and every renewal move <code>NEXT_ATTEMPT_AT</code> along with
   * <code>LEASED_UNTIL</code>. Without that, the poller would hear "due now" for as long as
   * the dispatch runs. It would ask again after its shortest sleep. That is the repeated
   * question which decision 19 in the repository's DECISIONS.md forbids.
   */
  private static final String SELECT_NEXT_ATTEMPT = """
      SELECT MIN(NEXT_ATTEMPT_AT) \
      FROM %s \
      WHERE STATUS = '%s' AND ATTEMPTS < ?""";

  /**
   * Takes the entry for as long as the lease lasts. The condition is the lease and not the
   * number of attempts. A claim must say "nobody carries this right now". The attempts say
   * something else, because they are written when an attempt ended.
   * <p>
   * <code>NEXT_ATTEMPT_AT</code> moves with the lease. So the poller computes its sleep from
   * a moment where there is something to do again (see {@link #SELECT_NEXT_ATTEMPT}).
   */
  private static final String CLAIM_ENTRY = """
      UPDATE %s \
      SET LEASED_BY = ?, LEASED_UNTIL = ?, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND STATUS = '%s' AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?)""";

  /**
   * Moves the lease of a running dispatch along, one write per tick of
   * {@link DispatchLease}. <code>LEASED_BY</code> is in the condition. So a node whose lease
   * ran out and was taken over renews nothing, and learns that it lost the entry.
   */
  private static final String RENEW_LEASE = """
      UPDATE %s \
      SET LEASED_UNTIL = ?, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND LEASED_BY = ? AND STATUS = '%s'""";

  /**
   * What the claim won, read again by its ID. See {@link #claim(Connection, Entry)} for why
   * this is not the row which the select of the due entries returned.
   */
  private static final String SELECT_CLAIMED_ENTRY = """
      SELECT WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, CREATED_AT \
      FROM %s WHERE ID = ?""";

  /**
   * Marking an entry DONE closes its window of deduplication. DEDUP_KEY gets the entry's own
   * ID, so a repetition of the same operation can be planned again. IDEMPOTENCY_KEY keeps the
   * key readable for whoever reads the table in a support case.
   * <p>
   * The attempt is also counted here, because it ended here. And the lease is given back
   * here.
   * <p>
   * <code>LEASED_BY</code> is in the condition, for the same reason as in
   * {@link #MARK_ENTRY_BLOCKED} and {@link #RESCHEDULE_ENTRY}. A node which lost the entry
   * writes nothing.
   * <p>
   * <code>LAST_FAILURE</code> is emptied, because the last attempt went through. An entry
   * which failed before and was dispatched in the end no longer says anything about a
   * failure.
   */
  private static final String MARK_ENTRY_DONE = """
      UPDATE %s \
      SET STATUS = '%s', DONE_AT = ?, DEDUP_KEY = ID, ATTEMPTS = ATTEMPTS + 1, \
      LEASED_BY = NULL, LEASED_UNTIL = NULL, LAST_FAILURE = NULL \
      WHERE ID = ? AND LEASED_BY = ?""";

  /**
   * Blocking releases DEDUP_KEY, just like marking an entry DONE does. The reason is easy to
   * miss. The key is what refuses a second schedule of the same operation. A blocked entry
   * which kept it would silence exactly the repetition which the application needs. The
   * application would ask, the outbox would answer no, and that answer looks exactly like a
   * correct deduplication. The row stays for whoever repairs it. The new attempt of the
   * operation is a row of its own.
   * <p>
   * The attempt which led here is counted. The lease is given back, so that no node holds
   * the row which has nothing left to do with it. <code>LAST_FAILURE</code> gets the reason
   * in the same write. So whoever finds the row reads why, without the log (see
   * {@link LastFailure}).
   */
  private static final String MARK_ENTRY_BLOCKED = """
      UPDATE %s \
      SET STATUS = '%s', DEDUP_KEY = ID, ATTEMPTS = ATTEMPTS + 1, \
      LEASED_BY = NULL, LEASED_UNTIL = NULL, LAST_FAILURE = ? \
      WHERE ID = ? AND LEASED_BY = ?""";

  /**
   * Says when the entry is to be read again after an attempt which did not get through. It
   * counts that attempt and gives the lease back. All three happen in one write. An entry
   * which is due again while it is still leased would wait until the lease ends.
   * <p>
   * <code>LEASED_BY</code> is in the condition of this write and of the two marks above. So a
   * node whose lease was taken over writes nothing. Its dispatch runs to its end. But what
   * the entry says is the business of the node which holds it now. An attempt which failed
   * here must not schedule an entry which the other node has just finished. It must not
   * block one either.
   * <p>
   * <code>LAST_FAILURE</code> gets the reason of this attempt. So an entry which is still
   * being repeated says why it hangs.
   */
  private static final String RESCHEDULE_ENTRY = """
      UPDATE %s \
      SET NEXT_ATTEMPT_AT = ?, ATTEMPTS = ATTEMPTS + 1, LEASED_BY = NULL, LEASED_UNTIL = NULL, \
      LAST_FAILURE = ? \
      WHERE ID = ? AND LEASED_BY = ?""";

  /**
   * Says when the entry is to be read again, after its BPMS did not report the workflow yet.
   * It gives the lease back. It is {@link #RESCHEDULE_ENTRY} without the count. Waiting for a
   * read model is not a failed attempt. Counting it would block entries after a few minutes
   * of a stopped exporter. The time the entry waited is read from <code>CREATED_AT</code>
   * instead. It does write the answer into <code>LAST_FAILURE</code>, because "the BPMS does
   * not report the workflow yet" is why such an entry waits.
   */
  private static final String RESCHEDULE_ENTRY_WITHOUT_COUNTING = """
      UPDATE %s \
      SET NEXT_ATTEMPT_AT = ?, LEASED_BY = NULL, LEASED_UNTIL = NULL, LAST_FAILURE = ? \
      WHERE ID = ? AND LEASED_BY = ?""";

  /**
   * What the housekeeping picks up: the entries which were dispatched long enough ago. The
   * limit is not part of it, because the three databases write a limit in three ways.
   * {@link io.vanillabp.integration.adapter.migration.jdbc.JdbcDialect} adds it.
   */
  private static final String EXPIRED_DONE_ENTRIES_FROM = "FROM %s";

  private static final String EXPIRED_DONE_ENTRIES_WHERE = "STATUS = '%s' AND DONE_AT < ?";

  /**
   * The entries written before the column existed which still need it. They wait or they are
   * blocked, they name a payload among their arguments, and the column is empty. A
   * dispatched entry is left out on purpose. Its payload went with the dispatch, so nothing
   * asks about it any more. And the history is the large part of the table.
   */
  private static final String ENTRIES_MISSING_THEIR_PAYLOAD_REFERENCE_FROM = "FROM %s";

  private static final String ENTRIES_MISSING_THEIR_PAYLOAD_REFERENCE_WHERE = "PAYLOAD_REFERENCE IS NULL AND STATUS <> '%s' AND ARGS LIKE ?";

  /**
   * The pattern which finds an entry whose arguments name a payload. It is the argument's own
   * name, so a rename of that constant changes this statement as well.
   */
  private static final String ARGS_NAMING_A_PAYLOAD = "%"
      + PhaseTwoCall.ARG_PAYLOAD_REFERENCE
      + "%";

  private static final String WRITE_PAYLOAD_REFERENCE = "UPDATE %s SET PAYLOAD_REFERENCE = ? WHERE ID = ?";

  /**
   * How many entries one round of the backfill reads. It is the same thousand with which the
   * housekeeping starts a night, for the same reason. A limit keeps one statement short on
   * every database.
   */
  private static final int BACKFILL_PER_ROUND = 1_000;

  /**
   * How many dispatched entries the housekeeping still owes. A window publishes this number
   * when it closes. It reads the index over STATUS and DONE_AT, which the delete above reads
   * as well.
   */
  private static final String COUNT_EXPIRED_DONE_ENTRIES = """
      SELECT COUNT(*) FROM %s WHERE STATUS = '%s' AND DONE_AT < ?""";

  /**
   * The column in which an entry names its payload. The reference travels among the
   * arguments as well, where identifiers travel (decision 62 in the repository's
   * DECISIONS.md). This column holds the same value in a shape which an index can reach. The
   * housekeeping asks the entries whether one of them still names a payload. Inside a column
   * of text that question is a scan (decision 76).
   */
  public static final String PAYLOAD_REFERENCE_COLUMN = "PAYLOAD_REFERENCE";

  /**
   * The column in which an entry keeps the reason of its last failed attempt. So a blocked
   * entry says why it is blocked, without the log (see {@link LastFailure}).
   */
  public static final String LAST_FAILURE_COLUMN = "LAST_FAILURE";

  /**
   * The columns which a later version of VanillaBP added to this table. A table created by an
   * earlier version has neither, and every poll would fail on a column which is not there.
   * So this is said at startup, with the statement which repairs it, and not at the first
   * dispatch.
   */
  private static final List<AddedColumn> ADDED_COLUMNS = List
      .of(
          new AddedColumn(
              "LEASED_BY", "VARCHAR(255) (nullable: an entry nobody is dispatching is leased by nobody)", "an entry cannot be claimed, so nothing is dispatched at all"),
          new AddedColumn(
              "LEASED_UNTIL", "TIMESTAMP (the type your database uses for the existing column NEXT_ATTEMPT_AT), nullable", "an entry cannot be claimed, so nothing is dispatched at all"),
          new AddedColumn(
              PAYLOAD_REFERENCE_COLUMN, "VARCHAR(36) (nullable: an entry which carries no payload names none)", "the housekeeping cannot tell which payloads are still needed, so it removes none of them and the payload table grows"),
          new AddedColumn(
              LAST_FAILURE_COLUMN, "VARCHAR(%d) (nullable: an entry whose last attempt went through has no failure)"
                  .formatted(
                      LastFailure.MAX_BYTES), "no failed attempt can be written down, so a failing entry is neither repeated nor blocked"));

  /**
   * A column which this version of VanillaBP reads and an older table does not have.
   *
   * @param name The column
   * @param definition What to add it as, in the words of whoever writes the statement
   * @param whatIsLost What does not work while it is missing
   */
  private record AddedColumn(String name, String definition, String whatIsLost) {
  }

  /**
   * The indexes by which this table is read. There is one list, because both the DDL which
   * creates them and the startup check which asks an existing table for them read it. A
   * second list would sooner or later name something which the other does not. Each of them
   * filters STATUS and then reads a moment, in that order. Each moment gets an index of its
   * own, because one index over two of them would serve neither question.
   * <p>
   * The list is public because a test asks the database whether the indexes are really there.
   * A test which wrote their names down again would keep its own answer while this one moved
   * (see {@code OutboxSleepsWhileNothingIsDueTest#theQuestionsOfThePollerAreIndexed} of the
   * Quarkus integration tests).
   */
  public static final List<TableIndex> INDEXES = List
      .of(
          new TableIndex("_DUE", List.of("STATUS", "NEXT_ATTEMPT_AT")),
          new TableIndex("_AGE", List.of("STATUS", "DONE_AT")),
          new TableIndex("_OLDEST", List.of("STATUS", "CREATED_AT")),
          new TableIndex("_PAYLOAD_REF", List.of(PAYLOAD_REFERENCE_COLUMN)));

  /**
   * One index of the outbox table. It is named after that table, so two outboxes on one
   * schema keep their indexes apart the same way as their tables.
   *
   * @param suffix What is appended to the table name to name the index
   * @param columns The columns it spans, in the order the statements read them
   */
  public record TableIndex(String suffix, List<String> columns) {

    /**
     * The name this index carries on a table.
     *
     * @param tableName The table this outbox writes into
     * @return The name the index carries on that table
     */
    public String nameOn(
        final String tableName) {

      return tableName + suffix;

    }

    /**
     * The statement which adds this index to a table.
     *
     * @param tableName The table this outbox writes into
     * @return The statement which creates the index on that table
     */
    public String createOn(
        final String tableName) {

      return "CREATE INDEX %s ON %s (%s)"
          .formatted(nameOn(tableName), tableName, String.join(", ", columns));

    }

  }

  private final JdbcConnectionAccess connections;

  private final PhaseTwoOutboxProperties properties;

  private final Supplier<PhaseTwoRouter> phaseTwoRouter;

  /**
   * What a blocked entry is counted into. It is a supplier and not the value itself, because
   * metrics are optional on both platforms. The bean may be resolved later than this
   * dispatcher is built.
   */
  private final Supplier<VanillaBpMetrics> metrics;

  /**
   * Which store an operator reads in the meters of this dispatcher. The platform names its
   * own class, because that is the bean which an application sees.
   */
  private final String storeName;

  private final JdbcPhaseTwoPayloadStore payloadStore;

  private final String tableName;

  private final String selectDueEntries;

  private final String selectNextAttempt;

  private final String claimEntry;

  private final String renewLease;

  private final String selectClaimedEntry;

  private final String markEntryDone;

  private final String markEntryBlocked;

  private final String rescheduleEntry;

  private final String rescheduleEntryWithoutCounting;

  private final String countExpiredDoneEntries;

  /**
   * Where this node says that it cleans up this store tonight. So no other node measures its
   * work at the same time.
   */
  private final JdbcHousekeepingLease housekeepingLease;

  /**
   * What removes the dispatched entries and the orphaned payloads, and when.
   */
  private final OutboxHousekeeping housekeeping;

  /**
   * The limited delete of the dispatched entries, built on the first housekeeping run. It
   * needs the database product, which is read from a connection. So it cannot be built in
   * the constructor next to the other statements.
   */
  private String deleteExpiredDoneEntries;

  /**
   * How many entries {@link #deleteExpiredDoneEntries} was built for. The limit is part of
   * the statement on every database, so a batch of another size builds it again.
   */
  private int deleteExpiredDoneEntriesBoundedAt;

  /**
   * What guards the two fields above. It is a lock of its own and not this dispatcher. The
   * poll holds the dispatcher's monitor while it hands entries to the lanes. It waits there
   * when a lane is full. The housekeeping runs on a thread of its own and must not queue
   * behind that.
   */
  private final Object deleteExpiredDoneEntriesLock = new Object();

  private final DueEntryPoller poller;

  private final DispatchLanes lanes;

  /**
   * What a running dispatch holds its entry with, and what keeps that hold alive while the
   * dispatch runs.
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
   * @param createdAt When the entry was written. A replacing call sets it again, because the
   *          row then carries a younger operation
   * @param heldBefore Who held this entry before this poll read it, or <code>null</code>
   *          where nobody did. It is the second half of "somebody has had this entry
   *          already". An attempt which ended is counted. An attempt in whose middle a node
   *          died left its name here and nothing else
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
     * Whether a dispatch has had this entry before. Then a START probes the BPMS it was
     * meant for, instead of starting a second workflow.
     *
     * @return Whether an attempt ended or a holder disappeared
     */
    boolean wasTakenBefore() {

      return (attempts > 0) || (heldBefore != null);

    }

    /**
     * What this entry is ordered by: its workflow aggregate. An entry which names none,
     * like a broadcast signal, is ordered by its BPMN process instead. So two signals of one
     * process still leave in the order they were planned.
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
   * Builds the dispatcher of one JDBC outbox: the statements for the given table, the
   * poller, the lanes, and the renewal of the leases. None of the three does anything before
   * {@link #start()}, and the table is not touched before {@link #prepareSchema()}.
   *
   * @param connections How this platform hands out a connection. It is used here outside
   *          any transaction of the application
   * @param properties The bound <code>vanillabp.outbox</code> section
   * @param tableName The table which is polled, the one its store writes into
   * @param payloadStore Where the bytes of a call with a payload are kept
   * @param phaseTwoRouter The router which gets a claimed entry
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
    this.claimEntry = CLAIM_ENTRY.formatted(tableName, STATUS_OPEN);
    this.renewLease = RENEW_LEASE.formatted(tableName, STATUS_OPEN);
    this.selectClaimedEntry = SELECT_CLAIMED_ENTRY.formatted(tableName);
    this.markEntryDone = MARK_ENTRY_DONE.formatted(tableName, STATUS_DONE);
    this.markEntryBlocked = MARK_ENTRY_BLOCKED.formatted(tableName, STATUS_BLOCKED);
    this.rescheduleEntry = RESCHEDULE_ENTRY.formatted(tableName);
    this.rescheduleEntryWithoutCounting = RESCHEDULE_ENTRY_WITHOUT_COUNTING.formatted(tableName);
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
   * Creates the outbox table, the payload table and the housekeeping table. If the
   * application manages its schema itself, it checks that they exist instead.
   * <p>
   * The platform calls this BEFORE the deployment pipeline runs. A workflow may be started
   * as soon as the application is up, and the entry needs its table.
   */
  public void prepareSchema() {

    if (properties.isCreateSchema()) {
      createTableIfNotExists();
      payloadStore.createSchemaIfNotExists();
      housekeepingLease.createSchemaIfNotExists();
    } else {
      // the application creates its schema itself. A missing table then means a deployment
      // which forgot to apply the migration. That is said at startup, and not at the first
      // workflow start
      validateTableExists();
      payloadStore.validateSchemaExists();
      housekeepingLease.validateSchemaExists();
    }
    fillPayloadReferencesOfWaitingEntries();

  }

  /**
   * Starts polling. The platform calls this once the BPMN resources are deployed and the
   * workflow processing has started. So nothing recovered goes to a BPMS which has not seen
   * the models yet.
   */
  public void start() {

    poller.start();
    housekeeping.start();

  }

  /**
   * Stops the poller, the lanes and the renewal of the leases. What a lane still held stays
   * OPEN in the table, and it keeps its lease until the lease runs out. Then the next start
   * of this node, or a poll of another node, takes it.
   */
  public void stop() {

    poller.stop();
    lanes.stop();
    lease.stop();
    housekeeping.stop();

  }

  /**
   * Moves the next poll forward to now. It is used right after a commit, where the entry
   * which was just written wants to go out at once.
   */
  public void triggerPoll() {

    poller.somethingIsDueAt(Instant.now());

  }

  /**
   * When this store owes something: the due time of the earliest entry which waits for its
   * dispatch. It is one aggregate query, answered from the index which this store creates
   * over STATUS and NEXT_ATTEMPT_AT.
   * <p>
   * The end of the retention of a DONE entry is not a reason to wake up. A wake-up only
   * dispatches (see decision 42 in the repository's DECISIONS.md). The DONE entries are
   * deleted in the night window of the housekeeping (see decision 91 in the repository's
   * DECISIONS.md).
   *
   * @return The moment, or <code>null</code> where nothing waits for its dispatch
   */
  private Instant earliestDueAt() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(selectNextAttempt)) {
        statement.setInt(1, properties.getBlockAfterAttempts());
        try (var resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          final var earliest = resultSet.getTimestamp(1);
          return earliest == null ? null : earliest.toInstant();
        }
      }
    } catch (final SQLException e) {
      // the poll which follows reports the same problem with its own message. A poller which
      // stops asking is worse than one which asks at the configured maximum
      log.debug("Could not read when the next phase-two outbox entry of table '{}' is due", tableName, e);
      return null;
    } finally {
      release(connection);
    }

  }

  private void createTableIfNotExists() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      // whether the table exists is checked through the JDBC metadata, because not all
      // databases support 'CREATE TABLE IF NOT EXISTS' (for example Oracle, SQL Server)
      if (JdbcSchema.tableExists(connection, tableName)) {
        validateColumns(connection);
        reportMissingIndexes(connection);
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(createTableStatement(connection, tableName));
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
              Could not create the phase-two outbox table '%s'! Set '%s' \
              to 'false' and manage the schema manually if the DDL is not suitable for your database."""
              .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Whether the DDL failed because another instance created the table between the check and
   * the statement. Two instances may start together, in a rolling deployment or a scale-up
   * from zero. Both see no table, and both create it. The boot of the loser must not end
   * because of that. It just has nothing left to do (see
   * {@link JdbcSchema#tableExistsQuietly(Connection, String)}).
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
   * Checks that the outbox table exists AND has the columns which this version writes. This
   * is for an application which creates its schema itself. The message names the table, the
   * property which would have created it, and the artifact with the statements.
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
            - let VanillaBP create the table by setting '%s' to \
            'true' (the default)."""
            .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY));

  }

  /**
   * Checks the columns which a later version of VanillaBP added to this table. Unlike a
   * missing index, this is a failure and not a warning. An entry cannot be claimed without
   * these columns. So an application which booted anyway would dispatch nothing and say
   * nothing either.
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
   * Names the indexes which this table needs and does not have, with the statement which
   * adds each of them. A table created by an earlier version of VanillaBP lacks the ones
   * which that version did not know. The question behind such an index is then a
   * sequential scan, which grows with everything the table ever held. Nobody sees that cost
   * until the table is large. It is a warning and not a failure. The application runs
   * correctly without the indexes. And creating an index on a large table takes a lock. That
   * is a decision, and a boot should not make it behind its operator's back.
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
   * Builds the CREATE TABLE statement with a timestamp type which suits the database. The
   * <code>TIMESTAMP</code> of SQL Server is a row version, not a date and time. The
   * <code>TIMESTAMP</code> of MySQL has odd automatic initialization and ends in 2038. The
   * idempotency key is limited to 512 characters (2048 bytes with utf8mb4), to stay below
   * the key-length limit of a unique index in MySQL, which is 3072 bytes.
   * <p>
   * It is public for the same reason as {@link #INDEXES}. A test which needs the table
   * before the application boots builds it from here. So the test cannot describe a table
   * which this store no longer writes (see {@code OutboxRecoveryOrderingTest} of the Quarkus
   * integration tests).
   *
   * @param connection The connection used to detect the database
   * @param tableName The table to create
   * @return The CREATE TABLE statement
   * @throws SQLException If the database cannot be asked what it is
   */
  public static String createTableStatement(
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
        LEASED_UNTIL %s, \
        LAST_FAILURE VARCHAR(%d))"""
        .formatted(
            tableName,
            timestampType,
            timestampType,
            timestampType,
            timestampType,
            LastFailure.MAX_BYTES);

  }

  /**
   * Claims all due entries and hands each of them to the lane of its aggregate. It deletes
   * nothing. The housekeeping removes the DONE entries in its night window. Exceptions are
   * caught to keep the poller alive.
   * <p>
   * This thread claims, and a lane dispatches. The order matters. An entry is claimed before
   * it is handed over. So the claim of the next poll, here or on another node, finds it
   * leased and leaves it alone.
   * <p>
   * A lane whose queue is full makes this thread wait. That waiting is the back pressure of
   * a backlog which arrives faster than it leaves. It keeps the backlog in the table, where
   * it can be read. Every step of this thread borrows its connection only for the moment it
   * needs it. So the waiting holds no connection. A poller which kept one would take it from
   * the lanes, and the lanes need a connection for every write of a dispatch.
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
   * Claims the entry and hands it to the lane of its aggregate, with the renewal of its lease
   * already running.
   * <p>
   * The renewal starts here and not where the lane picks the entry up. Between the two, the
   * entry waits in the lane's queue. A wait longer than the lease would let another node
   * claim an entry which this one is about to dispatch, and both would carry out the
   * operation. The queue is short, so the wait is short as well. But short is not the same
   * as impossible, and nobody is promised anything about the queue length.
   * <p>
   * Where no lane takes the entry because this node is stopping, the renewal ends here too.
   * The log says which operation waits for its lease to run out.
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
      // nothing will dispatch this entry, so nothing would end the renewal either
      held.close();
      throw e;
    }
    if (!taken) {
      // the same as above, for the one case which is no failure: this node is stopping, and
      // the lanes take nothing more. The renewal is closed here, and does not rely on the
      // order which stop() happens to have. That would be an agreement between two calls,
      // and it holds nothing
      held.close();
      reportTheEntryNoLaneTook(claimed);
    }

  }

  /**
   * Says that an entry which this poll claimed will not be dispatched, because the lanes take
   * nothing any more.
   * <p>
   * Nothing is lost. The entry stays OPEN and keeps its lease until the lease runs out. Then
   * the next poll of this node or of another one takes it. What it costs is that wait, one
   * <code>vanillabp.outbox.attempt-frequency</code>. This line tells that to whoever reads
   * the log of a shutdown.
   *
   * @param entry The claimed entry which no lane took
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
   * The reference has always travelled among the arguments, and it still does. The
   * housekeeping asks the column. So an entry written by an earlier version names its
   * payload where the dispatch reads it, but not where the housekeeping looks. Then that
   * payload would be removed as an orphan, and a dispatch which is still to come would lose
   * its bytes.
   * <p>
   * <strong>What this costs on a large table.</strong> Only the entries which still WAIT are
   * read. A dispatched entry gave its payload back at the dispatch, so nothing asks about it
   * any more. The waiting entries are the ones the outbox still owes something to, plus the
   * ones which somebody has to repair. On an application with a healthy outbox that is a
   * small number. On one with an unhealthy outbox it is a known number. The history, which
   * is the part that grows, is never touched. The work is done in rounds of
   * {@link #BACKFILL_PER_ROUND} rows, so no statement holds a lock on more rows than that. It
   * runs before the poller starts, because an entry dispatched in the meantime would leave a
   * payload behind.
   * <p>
   * Once the column is filled, it costs one read per start, and the index over the column
   * answers that read.
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
      // arguments, so their dispatch works. What is at risk is that the payload of one of
      // them is removed as an orphan. That is worth a line which somebody can act on
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
        // either nothing is left, or what is left names no payload after all. Both mean the
        // same here: another round would read the same rows again
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
   * One statement, on a connection which is borrowed for it and given back, like every
   * other step of this dispatcher. The limit is inside the statement, because
   * <code>DELETE ... LIMIT</code> is not portable SQL.
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
   * The entries were removed first. So nothing names their payloads now, and they go with
   * this call. What an entry still names is not removed by age at all. That is the payload
   * of an entry which waits, or of an entry which is blocked until somebody repairs it. The
   * payload store asks its own entries about that.
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
      // a number which could not be read stays a gap in the meter, not a zero
      log.debug("Could not count the dispatched entries of the outbox table '{}'", tableName, e);
      return OptionalLong.empty();
    } finally {
      release(connection);
    }

  }

  /**
   * The delete of at most so many dispatched entries. It is built once per limit and kept
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
   * Claims an entry with an optimistic update. It writes this node's name and the end of the
   * lease. So other pollers and other instances skip the entry. The claimed entry renews the
   * lease instead of losing it ({@link DispatchLease}).
   * <p>
   * What the claim won is then READ AGAIN, and the dispatch works with that. Between the
   * select of the due entries and this update, a younger call may have replaced the row
   * ({@link JdbcPhaseTwoOutboxStore}). The entry read a moment ago names the payload which
   * that call removed. A dispatch built from it would give the handler nothing where bytes
   * were promised. It costs one read by primary key per dispatch attempt. That is the price
   * of the rule that a dispatch reads what was written last.
   *
   * @param connection The connection to be used
   * @param entry The entry to be claimed
   * @return The entry as it stands now, or <code>null</code> where another poller won it
   */
  private Entry claim(
      final Connection connection,
      final Entry entry) throws SQLException {

    final var leaseEnds = lease.endsAt();
    try (var statement = connection.prepareStatement(claimEntry)) {
      // the claim leases the entry for one attempt-frequency. The renewal moves that moment
      // along until the dispatch is over. That keeps other pollers away for as long as this
      // node has something to do with the entry. The growing backoff belongs to a FAILED
      // dispatch and is written there. So a node which dies in the middle of a dispatch does
      // not leave behind the long distance of an attempt which nobody made
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
                // who held the row BEFORE this claim. The claim has just overwritten that with
                // this node, so it comes from the row which the select read
                entry.heldBefore())
            : null;
      }
    }

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On success
   * the entry is marked DONE. On failure it stays claimed and is retried after the
   * configured backoff, until it is blocked.
   * <p>
   * This runs on the lane of the entry's aggregate. It holds no connection while the router
   * works. The dispatch calls a BPMS over the network. A connection held that long would
   * make the pool the limit of how many entries may travel at once. The lease is renewed the
   * same way, with one short write per tick on a connection which is borrowed and given
   * back. So holding an entry costs no connection either.
   * <p>
   * The renewal ends here, before the write which says how the attempt ended. That write
   * needs a lease which has not run out, and the last renewal gave it one. It does not need
   * one which keeps growing. And a renewal which outlived the write would find the entry no
   * longer OPEN, and it would report a lease which it never lost.
   *
   * @param entry The claimed entry
   * @param held The renewal which started with the claim. It is closed when this attempt is
   *          over
   */
  private void dispatch(
      final Entry entry,
      final DispatchLease.Held held) {

    final String payloadReference;
    // everything the attempt does is inside, so the renewal is released however the
    // attempt ends, also when reading the arguments of the entry fails
    try (held) {
      final var args = PhaseTwoCall.deserializeArgs(entry.serializedArgs());
      payloadReference = args.get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
      // an entry which was taken before may have reached the BPMS already (recovered or
      // retried). The router then runs the START re-dispatch mitigation. The operation
      // travels as its persisted name. The router looks it up in the operation registry. An
      // unknown name gives a guiding error and leaves the entry for operations
      //
      // reading the payload is the one extra read which this form costs, and only for an
      // entry which names a payload: a lookup by primary key, once per dispatch attempt
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
      // the mark did not take, and either way the bytes stay. The entry may belong to another
      // node, which is still dispatching and would find a payload which is gone. Or the
      // database could not be asked. Then the entry is still OPEN here, and it needs those
      // bytes for the attempt after its lease
      return;
    }
    // the entry is dispatched, so its bytes have done their work. They are removed AFTER the
    // entry was marked, never before. A crash in between leaves a row which the housekeeping
    // deletes. The other order would leave an entry whose payload is gone
    if (payloadReference != null) {
      payloadStore.remove(payloadReference);
    }

  }

  /**
   * Writes down what a failed dispatch means for the entry. It is blocked where repeating
   * cannot help, where the attempts are used up, or where the entry waited too long for its
   * BPMS. Otherwise it gets a new due time.
   *
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void reportFailedDispatch(
      final Entry entry,
      final Exception e) {

    // the adapter said that repeating cannot help, so block it right away and not after
    // the configured attempts
    if (PhaseTwoPermanentFailure.isPermanent(e)) {
      if (!markBlocked(entry, LastFailure.of(e))) {
        return;
      }
      countBlockedEntry(entry.operation(), true);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked. How to find "
              + "it, open it again or delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE,
          entry.operation(),
          entry.bpmnProcessId(),
          entry.workflowModuleId(),
          entry.aggregateId(),
          entry.id(),
          e);
      return;
    }
    final var retryAfter = PhaseTwoRetryLater.retryAfter(e);
    if (retryAfter != null) {
      waitForTheReadModel(entry, retryAfter, e);
      return;
    }
    if (entry.attempts() + 1 >= properties.getBlockAfterAttempts()) {
      if (!markBlocked(entry, LastFailure.of(e))) {
        return;
      }
      countBlockedEntry(entry.operation(), false);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed {} times - the outbox entry '{}' is now blocked. How to find it, open it again or "
              + "delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE,
          entry.operation(),
          entry.bpmnProcessId(),
          entry.workflowModuleId(),
          entry.aggregateId(),
          entry.attempts() + 1,
          entry.id(),
          e);
      return;
    }
    // attempts() counts the attempts which ended before this one. So attemptDelay(0) is the
    // distance after the first failure. It is short, because most failures are
    // momentary
    final var retryIn = properties.attemptDelay(entry.attempts());
    if (!rescheduleAt(entry, Instant.now().plus(retryIn), rescheduleEntry, LastFailure.of(e))) {
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
   * Writes down that the BPMS of the entry does not report its workflow yet. The entry
   * waits for the window which the adapter named, and not for the configured backoff. The
   * attempt is not counted, because a read model which is behind is no failure of the
   * entry. A wait which never ends is ended by the time since the entry was written:
   * <code>vanillabp.outbox.wait-for-visibility-at-most</code>.
   *
   * @param entry The entry whose dispatch was answered with "not yet"
   * @param retryAfter The window the adapter named
   * @param e What the dispatch threw
   */
  private void waitForTheReadModel(
      final Entry entry,
      final Duration retryAfter,
      final Exception e) {

    final var now = Instant.now();
    if (properties.hasWaitedForVisibilityLongEnough(entry.createdAt(), now)) {
      if (!markBlocked(entry, LastFailure.ofAWaitWhichRanOut(Duration.between(entry.createdAt(), now), e))) {
        return;
      }
      countBlockedEntry(entry.operation(), false);
      log.error(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' waited {} "
              + "for its BPMS to report the workflow, which is longer than '{}' allows - the outbox "
              + "entry '{}' is now blocked. How to find it, open it again or delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE
              + " - the last answer was: {}",
          entry.operation(),
          entry.bpmnProcessId(),
          entry.workflowModuleId(),
          entry.aggregateId(),
          Duration.between(entry.createdAt(), now),
          PhaseTwoOutboxProperties.WAIT_FOR_VISIBILITY_AT_MOST_PROPERTY,
          entry.id(),
          e.getMessage());
      return;
    }
    if (!rescheduleAt(entry, now.plus(retryAfter), rescheduleEntryWithoutCounting, LastFailure.of(e))) {
      return;
    }
    log.info(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
            + "run yet - the outbox entry '{}' is dispatched again in {}, and it waits until {} at "
            + "most: {}",
        entry.operation(),
        entry.bpmnProcessId(),
        entry.workflowModuleId(),
        entry.aggregateId(),
        entry.id(),
        retryAfter,
        entry.createdAt().plus(properties.waitForVisibilityAtMost()),
        e.getMessage());

  }

  /**
   * Ticks the entry off. The poller is not told, unlike in
   * {@link #rescheduleAt(Entry, Instant, String, String)}. A DONE entry owes no dispatch, and
   * the housekeeping deletes it in its night window, not the poller.
   *
   * @param entry The entry which was dispatched
   * @return Whether the entry says that it is done now. It does where this node still held
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
    return true;

  }

  /**
   * Takes the entry out of what any poll reads. Nothing in VanillaBP moves it back.
   *
   * @param entry The entry which nobody is to try again
   * @param reason Why, as {@link LastFailure} words it
   * @return Whether the entry says that it is blocked now. It does where this node still
   *         held it and the database wrote it down
   */
  private boolean markBlocked(
      final Entry entry,
      final String reason) {

    final var outcome = update(markEntryBlocked, statement -> {
      statement.setString(1, reason);
      statement.setString(2, entry.id());
      statement.setString(3, lease.owner());
    });
    return theEntryNowSays(entry, outcome, "that it is blocked");

  }

  /**
   * Writes when the next attempt of an entry is due, with the given statement. The
   * statement either counts the attempt or does not. Then it tells the poller.
   * <p>
   * The poller has to be told, because the dispatch does not run on it. It decided how long
   * to rest when this attempt began, from the lease which the claim wrote. A due time
   * closer than that lease would otherwise wait until the lease ends. An adapter names such a
   * due time for a workflow which its BPMS cannot find yet.
   *
   * @param entry The entry whose dispatch did not get through
   * @param nextAttempt When it is to be read again
   * @param reschedule The update to run, {@link #RESCHEDULE_ENTRY} or
   *          {@link #RESCHEDULE_ENTRY_WITHOUT_COUNTING}
   * @param reason Why the attempt did not get through, as {@link LastFailure} words it
   * @return Whether the entry says its new due time. It does where this node still held it
   *         and the database wrote it down
   */
  private boolean rescheduleAt(
      final Entry entry,
      final Instant nextAttempt,
      final String reschedule,
      final String reason) {

    final var outcome = update(reschedule, statement -> {
      statement.setTimestamp(1, Timestamp.from(nextAttempt));
      statement.setString(2, reason);
      statement.setString(3, entry.id());
      statement.setString(4, lease.owner());
    });
    if (!theEntryNowSays(entry, outcome, "when it is due again")) {
      return false;
    }
    poller.somethingIsDueAt(nextAttempt);
    return true;

  }

  /**
   * Whether the entry has what a write of this dispatcher wanted to put on it. Where it does
   * not, this writes the line an operator reads.
   * <p>
   * The two ways in which a write does not take are told apart here, because only one of
   * them means the entry changed hands. A write which the database could not run leaves the
   * entry with this node, exactly as it was. So the caller must go on as if nothing had been
   * written, which is the truth. The next poll reads the entry again once its lease runs
   * out.
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
   * Says that an attempt ended, and that the database could not be asked to write down how.
   * <p>
   * The entry keeps its status, its due time and its lease. So the next poll of this node or
   * of another one reads it once that lease runs out. Where the attempt had reached the BPMS,
   * the operation then runs a second time. That is the at-least-once which this outbox
   * documents. In the meantime the entry must not lose its payload, because the dispatch it
   * waits for needs those bytes.
   *
   * @param entry The entry whose write did not run
   * @param whatTheWriteSaid What the entry would say now, if the write had got through
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
   * Says that an attempt ended on an entry which this node does not hold any more. So what it
   * wanted to write was dropped.
   * <p>
   * The renewal said the same earlier, at the moment the entry changed hands. This message
   * is the other end of it, and it is worth its own line. It names the operation which ran
   * twice. And it proves that the second run did not overwrite what the node which holds the
   * entry wrote.
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
   * Moves the lease of an entry which this node dispatches along, in a transaction of its
   * own. That is one write, on a connection which is borrowed and given back, once per tick
   * of {@link DispatchLease}.
   *
   * @param entryId The entry being dispatched
   * @param leaseEnd How long the lease is to last now
   * @return Whether this node still holds the entry. A write which matched no row says it
   *         does not any more. A write which could not be run at all says nothing. Then the
   *         renewal keeps trying, and does not give the entry up over one hiccup
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
   * Runs one statement on a connection of its own. A lane holds no connection between two
   * entries, so every write of a dispatch borrows one for the moment it needs it.
   * <p>
   * A failure here is logged and not thrown. It costs a repetition of an entry whose
   * dispatch already happened, and the contract of the outbox allows that. Throwing would
   * end the lane instead.
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
   * What became of one write of this dispatcher. There are three answers and not a number. A
   * write which matched no row and a write which nobody could run mean opposite things for
   * the entry. The first says that somebody else owns it now. The second says that the entry
   * is exactly as it was, and this node still holds it.
   */
  private enum WriteOutcome {

    /**
     * The row was changed. So this node held the entry, and what the write says stands.
     */
    WRITTEN,

    /**
     * The statement ran and matched no row. Every write of an ended attempt names the holder
     * of the lease. So this means the entry changed hands while the dispatch ran.
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
   * Hands the call to the core's router, and reports how long its entry waited for this
   * attempt, whether the attempt succeeded or threw. The wait counts from the moment the
   * entry was written. So a repeated attempt reports the whole wait of the operation, not
   * the time since the last try.
   * <p>
   * There is one report per entry, and the lanes change nothing about that. An entry which
   * travels next to another one waited as long as its own row says.
   *
   * @param call The call to dispatch
   * @param previouslyAttempted Whether the entry was dispatched before
   * @param writtenAt When the entry was written, or <code>null</code> where the row has no
   *          such moment
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
   * When an entry was written, as its row says. The column is NOT NULL in the table which
   * VanillaBP creates. So nothing comes back only where the application brought a table of
   * its own which leaves it empty. The attempt of such an entry is not measured. That is
   * better than measuring it from now.
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
   * Counts an entry on which this store gave up. The gauge of waiting entries drops at the
   * same moment. So without this counter the only number an operator watches would move as
   * if things had got better.
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
