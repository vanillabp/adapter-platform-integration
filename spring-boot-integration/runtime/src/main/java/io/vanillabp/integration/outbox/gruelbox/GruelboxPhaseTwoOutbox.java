package io.vanillabp.integration.outbox.gruelbox;

import java.util.OptionalLong;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;

import com.gruelbox.transactionoutbox.AlreadyScheduledException;
import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * JPA: delegates to a <a href="https://github.com/gruelbox/transaction-outbox">gruelbox
 * transaction-outbox</a> configured with Spring's transaction manager, so the outbox
 * entry is enlisted in the currently running local (JDBC) transaction.
 * <p>
 * The idempotency contract of {@link PhaseTwoOutbox} maps onto gruelbox's
 * <code>uniqueRequestId</code> mechanism: the {@link PhaseTwoCall#idempotencyKey()} is
 * used as unique request ID, enforced by a unique constraint of gruelbox's outbox
 * table. A duplicate schedule raises {@link AlreadyScheduledException} which is turned
 * into the contract's no-op (<code>false</code>). Successfully dispatched entries with
 * a unique request ID are retained by gruelbox until the configured retention
 * threshold passes (the contract's "DONE instead of delete").
 * <p>
 * <strong>Deduplication has to span the entries still waiting for their dispatch
 * only</strong>, and gruelbox' unique constraint spans its retained entries as well.
 * Its table has no column this store could move a dispatched key into, so the release
 * happens when it is needed: before scheduling, the store reads the row of that unique
 * request ID and looks at gruelbox' <code>processed</code> flag. A processed row is
 * DELETED - it has done its work, and its trail ends there, which is the price of
 * gruelbox owning the table - and the new operation is then scheduled. A row which is
 * not processed yet means an identical operation is still planned, and the schedule is
 * discarded. Both happen in the caller's transaction, on the connection
 * {@code DataSourceUtils} binds to it, so a rollback takes the release with it.
 * <p>
 * <strong>A younger call may take the waiting entry's place</strong> instead of being
 * discarded against it, where it says so
 * ({@link PhaseTwoCall#replacingWhatIsStillWaiting()}). Replacing is not in gruelbox'
 * API, so it is the row which goes: the waiting entry is DELETED and the younger call
 * is scheduled under the same unique request ID, in the caller's transaction, and the
 * payload of the entry which went is removed with it. What decides is gruelbox'
 * <code>version</code> column together with the register of
 * {@link GruelboxRedispatchAwareSubmitter}, and a dispatch is never waited for: see
 * {@link #deleteEntryNoDispatchHasTaken(PhaseTwoCall, WaitingEntry)} for why it takes
 * both. Where one of them says that a dispatch has the entry, the younger call is
 * scheduled with NO unique request ID, because the key belongs to the entry on its
 * way, and two calls then reach the handler where one was asked for.
 * <p>
 * The at-least-once guarantee is not weakened by that: a redispatch reads the very row
 * which is not processed yet, so gruelbox' own attempt bookkeeping carries it - never
 * the unique request ID. Which is also why the key VanillaBP derives is bounded to
 * {@link PhaseTwoCall#MAX_IDEMPOTENCY_KEY_LENGTH} characters: gruelbox refuses a longer
 * unique request ID before any database sees it.
 * <p>
 * A store built with the constructor which takes no data source cannot read that flag.
 * It falls back to gruelbox' own answer, which deduplicates against retained entries as
 * well - kept for tests, and named here so nobody mistakes it for the contract.
 * <p>
 * {@link PhaseTwoOutbox#adapterIdsOfPendingCalls(String, String)} is the one question of
 * the contract this store does not answer AT A START. gruelbox keeps a call as a
 * serialized invocation rather than in columns, so there is no adapter id to ask about
 * without reading and deserializing the whole table, which is the cost decision 19 in the
 * repository's DECISIONS.md rules out for a start, and a table of gruelbox' is not one
 * VanillaBP adds a column to. What the start would have said is said at the first dispatch
 * instead: the entry is deserialized there anyway, so the id it waits for is known, and an
 * id which is gone from the configuration is reported in the words the start uses - once
 * per adapter id, whatever the backlog. Decision 47 in the repository's DECISIONS.md says
 * why that is the answer and what the alternatives would have cost.
 * <p>
 * {@link PhaseTwoOutbox#ageOfOldestPendingCall()} is the other question this store
 * leaves unanswered, and this one it cannot answer at all. gruelbox keeps no moment of
 * writing: it puts that moment into <code>nextAttemptTime</code> and overwrites it the
 * first time a flush picks the entry up, so the oldest waiting entry - which is usually
 * one which was picked up and failed - no longer says when it was planned. Answering
 * from the entries nothing has touched yet would report a young age while old ones
 * stand next to them, which reads as an outbox that is up to date. So no age is
 * published for this store, and <code>vanillabp.outbox.pending</code> stays the number
 * to watch here. The wait of a dispatch is reported for the entries where the moment is
 * still there, which is every entry submitted right after its transaction committed
 * (see {@link GruelboxRedispatchAwareSubmitter#whenTheEntryWasWritten()}).
 */
@Slf4j
public class GruelboxPhaseTwoOutbox implements PhaseTwoOutbox {

  /**
   * Reads back what gruelbox wrote into its <code>invocation</code> column. gruelbox
   * builds the same one unless an application replaces the serializer of its persistor,
   * and where it did, an unreadable invocation costs a payload row the age sweep takes
   * (see {@link #payloadReferenceOf(String)}).
   */
  private static final com.gruelbox.transactionoutbox.InvocationSerializer INVOCATION_SERIALIZER = com.gruelbox.transactionoutbox.InvocationSerializer
      .createDefaultJsonSerializer();

  private final TransactionOutbox transactionOutbox;

  /**
   * Where gruelbox' table lives, needed to count the entries waiting for their
   * dispatch. <code>null</code> where the caller did not supply one - the pending
   * meter is then absent rather than wrong.
   */
  private final DataSource dataSource;

  /**
   * The table gruelbox stores its entries in.
   */
  private final String tableName;

  /**
   * Where the bytes of a call which carries a payload are written, in the transaction
   * which writes the entry. <code>null</code> where the caller supplied none - a call
   * carrying a payload is then refused with a message saying so, rather than reaching
   * the BPMS without it.
   */
  private final io.vanillabp.integration.spi.PhaseTwoPayloadStore payloadStore;

  /**
   * Creates an outbox which cannot count its pending entries - kept for tests.
   *
   * @param transactionOutbox The gruelbox transaction outbox
   */
  public GruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox) {

    this(transactionOutbox, null, null, null);

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @param dataSource Where gruelbox' table lives
   * @param tableName The table gruelbox stores its entries in
   */
  public GruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox,
      final DataSource dataSource,
      final String tableName) {

    this(transactionOutbox, dataSource, tableName, null);

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @param dataSource Where gruelbox' table lives
   * @param tableName The table gruelbox stores its entries in
   * @param payloadStore Where the payload of a call which carries one is written
   */
  public GruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox,
      final DataSource dataSource,
      final String tableName,
      final io.vanillabp.integration.spi.PhaseTwoPayloadStore payloadStore) {

    this.transactionOutbox = transactionOutbox;
    this.dataSource = dataSource;
    this.tableName = tableName;
    this.payloadStore = payloadStore;

  }

  /**
   * Counts the entries gruelbox has not processed yet. gruelbox has no API for it, so
   * the count reads its table directly - along the index it creates itself
   * (<code>IX_TXNO_OUTBOX_1</code> over <code>processed, blocked,
   * nextAttemptTime</code>), which is why one column is enough and no dialect-specific
   * literal is needed: the driver knows how to write a boolean into whatever type the
   * column has on this database.
   */
  @Override
  public OptionalLong pendingCalls() {

    if ((dataSource == null) || (tableName == null)) {
      return OptionalLong.empty();
    }
    final var countPending = "SELECT COUNT(*) FROM %s WHERE processed = ?".formatted(tableName);
    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(countPending)) {
      statement.setBoolean(1, false);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? OptionalLong.of(resultSet.getLong(1))
            : OptionalLong.empty();
      }
    } catch (final java.sql.SQLException e) {
      // a metric must never be the reason an application fails - the gauge reports
      // nothing for this collection and the next one tries again
      log.debug("Could not count the pending entries of gruelbox' outbox table '{}'", tableName, e);
      return OptionalLong.empty();
    }

  }

  /**
   * When gruelbox' next flush has something to do, which is what its dispatcher waits for.
   * <p>
   * Both halves of a flush read the same column, because gruelbox keeps both moments in
   * <code>nextAttemptTime</code>: an entry waiting for its dispatch carries its next attempt
   * there, and an entry which was dispatched carries the moment its retention runs out. What
   * differs is the <code>processed</code> flag, and they are therefore asked as two questions
   * instead of one: the index gruelbox creates for its own flush spans
   * <code>(processed, blocked, nextAttemptTime)</code>, so a question naming both flags is
   * answered from that index, while one naming only <code>blocked</code> would read the whole
   * table - and that cost grows with everything the table ever held.
   * <p>
   * A BLOCKED entry is left out of both, and that is the point of the predicate: it waits for a
   * person rather than for a clock, so a store which holds nothing else has nothing to be woken
   * for.
   *
   * @return The moment of the earliest entry, or <code>null</code> where nothing is owed -
   *         which is the answer a store without a data source gives as well, leaving its
   *         poller on the configured cap
   */
  public java.time.Instant earliestDueAt() {

    if ((dataSource == null) || (tableName == null)) {
      return null;
    }
    final var selectEarliest = "SELECT MIN(nextAttemptTime) FROM %s WHERE processed = ? AND blocked = ?"
        .formatted(tableName);
    try (var connection = dataSource.getConnection()) {
      final var nextAttempt = earliest(connection, selectEarliest, false);
      final var retentionRunsOut = earliest(connection, selectEarliest, true);
      if (nextAttempt == null) {
        return retentionRunsOut;
      }
      if (retentionRunsOut == null) {
        return nextAttempt;
      }
      return nextAttempt.isBefore(retentionRunsOut) ? nextAttempt : retentionRunsOut;
    } catch (final java.sql.SQLException e) {
      // the flush which follows reports the same problem with its own message, and a
      // poller which stops asking is worse than one which asks at the cap
      log.debug("Could not read the next attempt time of gruelbox' outbox table '{}'", tableName, e);
      return null;
    }

  }

  /**
   * The earliest moment one of the two kinds of entry wants something.
   *
   * @param connection The connection to ask on
   * @param query The aggregate over the table, taking the two flags
   * @param processed Whether to look at the entries which were dispatched already
   * @return The moment or <code>null</code> where there is no such entry
   */
  private java.time.Instant earliest(
      final java.sql.Connection connection,
      final String query,
      final boolean processed) throws java.sql.SQLException {

    try (var statement = connection.prepareStatement(query)) {
      statement.setBoolean(1, processed);
      statement.setBoolean(2, false);
      try (var resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        final var earliest = resultSet.getTimestamp(1);
        return earliest == null ? null : earliest.toInstant();
      }
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    final var idempotencyKey = call
        .idempotencyKey()
        .orElse(null);
    // the key of the entry this call ends up carrying: its own, unless an entry a
    // dispatch has taken still holds it - then this call takes no part in the
    // deduplication of that key, the way a call without one does not
    var uniqueRequestId = idempotencyKey;
    String replacedPayloadReference = null;
    if (idempotencyKey != null) {
      final var waiting = entryStillWaiting(call, idempotencyKey);
      if (waiting != null) {
        if (!call.replacesWhatIsStillWaiting()) {
          logDiscardedSchedule(call);
          return false;
        }
        if (deleteEntryNoDispatchHasTaken(call, waiting)) {
          replacedPayloadReference = waiting.payloadReference();
          logReplacedEntry(call);
        } else {
          logSecondEntryBesideAClaimedOne(call);
          uniqueRequestId = null;
        }
      }
    }
    try {
      transactionOutbox
          .with()
          .uniqueRequestId(uniqueRequestId)
          .schedule(GruelboxPhaseTwoDispatch.class)
          .dispatch(
              call.operation(),
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.adapterId(),
              PhaseTwoCall.serializeArgs(call.args()));
      // the entry is in, so the bytes it names may follow - on the connection bound to
      // this transaction, and only now, because a schedule discarded as a duplicate
      // must leave nothing behind
      if (call.hasPayload()) {
        requirePayloadStore(call).write(call);
      }
      // and the bytes of the entry which was replaced have no reader left: the entry
      // is gone, and no dispatch had ever taken it
      if (replacedPayloadReference != null) {
        requirePayloadStore(call).remove(replacedPayloadReference);
      }
      return true;
    } catch (AlreadyScheduledException e) {
      // two nodes scheduling the same operation at the same moment, or a store which
      // cannot read gruelbox' table (see the class javadoc)
      logDiscardedSchedule(call);
      return false;
    }

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
   * The payload store of this outbox, or a guiding error where it has none. An outbox
   * built without one is a test setup, and a call carrying a payload must not lose it
   * silently there either.
   *
   * @param call The call whose payload is about to be written
   * @return The payload store
   */
  private io.vanillabp.integration.spi.PhaseTwoPayloadStore requirePayloadStore(
      final PhaseTwoCall call) {

    if (payloadStore != null) {
      return payloadStore;
    }
    throw new IllegalStateException(
        """
            Phase two (%s) of BPMN process '%s' of workflow module '%s' carries a payload, and this             outbox was built without a payload store to put it in! The auto-configuration of             VanillaBP builds one; an application building its gruelbox outbox itself passes a             PhaseTwoPayloadStore to the constructor of this class."""
            .formatted(call.operation(), call.bpmnProcessId(), call.workflowModuleId()));

  }

  /**
   * The entry of this unique request ID which is still waiting for its dispatch, or
   * <code>null</code> where the key is free for a new one.
   * <p>
   * An entry gruelbox already processed is DELETED here - it has done its work, and its
   * trail ends there, which is the price of gruelbox owning the table - so the key is
   * free and this answers <code>null</code>.
   *
   * @return The waiting entry or <code>null</code>
   */
  private WaitingEntry entryStillWaiting(
      final PhaseTwoCall call,
      final String idempotencyKey) {

    if ((dataSource == null) || (tableName == null)) {
      // no way to look at gruelbox' own columns: gruelbox answers instead, which
      // deduplicates the retained entries as well and replaces nothing
      return null;
    }
    final var selectEntry = "SELECT id, processed, version, invocation FROM %s WHERE uniqueRequestId = ?"
        .formatted(tableName);
    final var connection = DataSourceUtils.getConnection(dataSource);
    try {
      final String entryId;
      final WaitingEntry waiting;
      try (var statement = connection.prepareStatement(selectEntry)) {
        statement.setString(1, idempotencyKey);
        try (var resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          entryId = resultSet.getString(1);
          waiting = resultSet.getBoolean(2)
              ? null
              : new WaitingEntry(entryId, resultSet.getInt(3), payloadReferenceOf(resultSet.getString(4)));
        }
      }
      if (waiting != null) {
        return waiting;
      }
      final var deleteEntry = "DELETE FROM %s WHERE id = ? AND processed = ?".formatted(tableName);
      try (var statement = connection.prepareStatement(deleteEntry)) {
        statement.setString(1, entryId);
        statement.setBoolean(2, true);
        // 0 rows: the dispatcher's retention cleanup got there first, which frees the
        // key just as well
        statement.executeUpdate();
      }
      log.debug(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' was "
              + "dispatched before - released the entry so this operation can be planned again",
          call.operation(),
          call.bpmnProcessId(),
          call.workflowModuleId(),
          call.workflowAggregateId());
      return null;
    } catch (final java.sql.SQLException e) {
      throw new IllegalStateException(
          """
              Could not look up the phase-two outbox entry of BPMN process '%s' of workflow module \
              '%s' in gruelbox' table '%s'!"""
              .formatted(call.bpmnProcessId(), call.workflowModuleId(), tableName), e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }

  }

  /**
   * Deletes the entry a younger call replaces, so its unique request ID is free for
   * that call - which is what replacing means on a store whose API does not know it.
   * <p>
   * Two things have to say that no dispatch has taken the entry, because gruelbox
   * reaches a dispatch two ways. A FLUSH pushes the entry back first, which counts
   * gruelbox' <code>version</code> up, and the delete carries <code>version = 0</code>
   * - gruelbox' own optimistic lock, so this is the very race the flush runs, decided
   * by the same column. A commit, on the other hand, submits the entry straight away
   * and writes nothing, so the row still reads as untouched while the dispatch holds
   * it; that one is asked of
   * {@link GruelboxRedispatchAwareSubmitter#isBeingDispatched(String)}.
   * <p>
   * Asking matters because gruelbox locks the row of an entry it dispatches and keeps
   * the lock until the handler returned: a delete which met it would make the
   * application's transaction wait for a remote call. The register answers for this
   * application, and the residual is an instance which dispatches an entry another
   * instance replaces in that window - a workflow written by two instances at once,
   * which VanillaBP names as the application's own business anyway.
   *
   * @return Whether the entry was deleted
   */
  private boolean deleteEntryNoDispatchHasTaken(
      final PhaseTwoCall call,
      final WaitingEntry waiting) {

    if ((waiting.version() > 0) || GruelboxRedispatchAwareSubmitter.isBeingDispatched(waiting.id())) {
      return false;
    }
    final var deleteEntry = "DELETE FROM %s WHERE id = ? AND version = 0 AND processed = ?".formatted(tableName);
    final var connection = DataSourceUtils.getConnection(dataSource);
    try (var statement = connection.prepareStatement(deleteEntry)) {
      statement.setString(1, waiting.id());
      statement.setBoolean(2, false);
      return statement.executeUpdate() == 1;
    } catch (final java.sql.SQLException e) {
      throw new IllegalStateException(
          """
              Could not replace the phase-two outbox entry of BPMN process '%s' of workflow module \
              '%s' in gruelbox' table '%s'!"""
              .formatted(call.bpmnProcessId(), call.workflowModuleId(), tableName), e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }

  }

  /**
   * The payload reference an entry of this store names, read out of the invocation
   * gruelbox serialized. There is no column for it: gruelbox keeps a call as one
   * serialized invocation, and the arguments of that invocation are the six strings
   * {@link GruelboxPhaseTwoDispatch#dispatch} takes, the last of them being the
   * serialized {@link PhaseTwoCall#args()}.
   * <p>
   * Read with gruelbox' own serializer, which is what wrote it. An entry this store did
   * not write, or one written by a persistor built with a serializer of the
   * application's own, is not understood here - the reference then stays unknown, the
   * replaced payload is left to the age sweep of the payload store, and nothing else
   * changes.
   *
   * @param invocation The serialized invocation of the entry
   * @return The reference or <code>null</code> where the entry names none
   */
  private String payloadReferenceOf(
      final String invocation) {

    if (invocation == null) {
      return null;
    }
    try (var reader = new java.io.StringReader(invocation)) {
      final var args = INVOCATION_SERIALIZER.deserializeInvocation(reader).getArgs();
      if ((args == null) || (args.length < 6) || !(args[5] instanceof final String serializedArgs)) {
        return null;
      }
      return PhaseTwoCall.deserializeArgs(serializedArgs).get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    } catch (final Exception e) {
      log
          .debug(
              "Could not read the payload reference of a phase-two outbox entry of gruelbox' table "
                  + "'{}' - a payload of that entry is left to the age sweep of the payload store",
              tableName,
              e);
      return null;
    }

  }

  /**
   * What this store needs to know about the entry a younger call meets: which row it
   * is, whether a dispatch has taken it (gruelbox counts the version up when it does),
   * and which payload it names.
   *
   * @param id The entry's own id
   * @param version gruelbox' optimistic-lock counter - zero means untouched
   * @param payloadReference The reference of its payload, or <code>null</code>
   */
  private record WaitingEntry(String id, int version, String payloadReference) {
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

}
