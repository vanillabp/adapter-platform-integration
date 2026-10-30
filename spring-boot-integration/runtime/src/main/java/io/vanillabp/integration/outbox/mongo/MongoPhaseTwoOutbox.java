package io.vanillabp.integration.outbox.mongo;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * MongoDB for aggregate persistence. The outbox entry is
 * written with {@link MongoTemplate}, which takes part in the running Spring-managed
 * MongoDB transaction. The entry stores all fields of the {@link PhaseTwoCall},
 * including the operation discriminator and the elected adapter ID.
 * <p>
 * A unique index on the entry's <code>dedupKey</code> enforces the deduplication. So it
 * spans the entries which still wait for their dispatch, as the contract of
 * {@link PhaseTwoOutbox} demands. While the entry waits, that field carries the
 * idempotency key. Once the dispatcher marked the entry DONE, it carries the entry's own
 * id. <code>idempotencyKey</code> keeps the key readable for support. The at-least-once
 * guarantee does not change, because a redispatch reads the same document. The entry's
 * status and attempt count carry that guarantee, never the key.
 * <p>
 * A read before the insert finds a duplicate schedule and turns it into the contract's
 * no-op (<code>false</code>). Within a MongoDB transaction, a
 * {@link DuplicateKeyException} would abort the whole transaction. So the unique index
 * is only the safety net for duplicates which arrive at the same time.
 * <p>
 * <strong>A younger call may take the waiting entry's place</strong> instead of being
 * discarded against it, where it says so
 * ({@link PhaseTwoCall#replacingWhatIsStillWaiting()}). The document keeps its id and
 * its key and gets everything the dispatch reads, and the payload of the entry it
 * replaced is removed with it. Two fields decide: <code>attempts</code> and the lease.
 * The dispatcher takes the lease when it claims an entry, and it counts the attempt when
 * the attempt ends. So zero attempts and a free lease mean that no dispatch has read this
 * entry and none holds its payload. The update carries both conditions. That makes it
 * the same atomic claim. If a poller wins the document, the update matches nothing. Then
 * the call becomes an entry of its own, with <code>dedupKey</code> set to its own id,
 * because the key belongs to the entry on its way.
 * <p>
 * <strong>Note:</strong> MongoDB transactions need a replica set. Without a MongoDB
 * transaction (no <code>MongoTransactionManager</code>, or a standalone server), the
 * entry is written at once, and dispatching is best-effort. A crash between saving the
 * aggregate and writing the entry may lose the phase-two call. And a rollback of the
 * aggregate does not remove an entry which was already written.
 */
@Slf4j
public class MongoPhaseTwoOutbox implements PhaseTwoOutbox {

  private final MongoTemplate mongoTemplate;

  private final MongoPhaseTwoOutboxDispatcher dispatcher;

  /**
   * The collection which stores the outbox entries
   * (<code>vanillabp.outbox.mongo.collection</code>). Every outbox instance needs its own
   * collection. Two dispatchers which poll the same collection would compete and
   * dispatch twice.
   */
  private final String collection;

  /**
   * Built by {@link MongoPhaseTwoOutboxAutoConfiguration} where the workflow aggregates of
   * this application live in MongoDB. Also built by an application which builds an outbox
   * of its own, on a collection which nothing else writes into.
   *
   * @param mongoTemplate The template the entries are written and read through. The
   *          aggregates use the same one. So both take part in one MongoDB transaction
   *          wherever the deployment is a replica set
   * @param dispatcher What polls the collection. It is told after every commit that
   *          something is waiting, and it owns the payloads of the entries
   * @param collection The collection the entries go into, one per outbox
   */
  public MongoPhaseTwoOutbox(
      final MongoTemplate mongoTemplate,
      final MongoPhaseTwoOutboxDispatcher dispatcher,
      final String collection) {

    this.mongoTemplate = mongoTemplate;
    this.dispatcher = dispatcher;
    this.collection = collection;

  }

  /**
   * The adapter ids which the OPEN entries of one BPMN process are waiting for. An id which
   * is not configured any more was renamed or removed too early. Both leave the workflow of
   * a START entry unstarted.
   */
  @Override
  public Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var query = Query
        .query(
            Criteria
                .where("workflowModuleId")
                .is(workflowModuleId)
                .and("bpmnProcessId")
                .is(bpmnProcessId)
                .and("status")
                .is(PhaseTwoOutboxEntry.STATUS_OPEN)
                .and("adapterId")
                .ne(null));
    return new LinkedHashSet<>(
        mongoTemplate.findDistinct(query, "adapterId", collection, String.class));

  }

  /**
   * Whether an OPEN entry of the workflow is in the collection. The template takes part in
   * the running MongoDB transaction, where there is one. So an entry this transaction wrote
   * a moment ago is found as well.
   */
  @Override
  public Optional<Boolean> hasPendingCallsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    try {
      return Optional
          .of(mongoTemplate
              .exists(
                  Query
                      .query(Criteria
                          .where("status")
                          .is(PhaseTwoOutboxEntry.STATUS_OPEN)
                          .and("workflowModuleId")
                          .is(workflowModuleId)
                          .and("bpmnProcessId")
                          .is(bpmnProcessId)
                          .and("aggregateId")
                          .is(workflowAggregateId)),
                  collection));
    } catch (final RuntimeException e) {
      // the caller only asks because phase one refused. Without an answer that refusal
      // stands, which is what happened before this question existed
      log.debug("Could not read whether entries of aggregate '{}' wait in the MongoDB phase-two outbox",
          workflowAggregateId, e);
      return Optional.empty();
    }

  }

  /**
   * Counts the entries which wait for their dispatch. It is a single count over the same
   * collection the dispatcher polls.
   */
  @Override
  public OptionalLong pendingCalls() {

    try {
      return OptionalLong
          .of(mongoTemplate
              .count(
                  Query.query(Criteria.where("status").is(PhaseTwoOutboxEntry.STATUS_OPEN)),
                  collection));
    } catch (final RuntimeException e) {
      // a metric must never be the reason an application fails
      log.debug("Could not count the pending entries of the MongoDB phase-two outbox", e);
      return OptionalLong.empty();
    }

  }

  /**
   * How long the oldest waiting entry has been waiting, read from its
   * <code>createdAt</code>. That is the moment the entry was written. A replacing call sets
   * it anew, because the document then carries a younger operation. It reads one document,
   * along the index over the status and that moment.
   */
  @Override
  public Optional<Duration> ageOfOldestPendingCall() {

    try {
      final var oldest = mongoTemplate
          .findOne(
              Query
                  .query(Criteria
                      .where("status")
                      .is(PhaseTwoOutboxEntry.STATUS_OPEN))
                  .with(Sort.by("createdAt"))
                  .limit(1),
              PhaseTwoOutboxEntry.class,
              collection);
      // nothing waiting means nothing is owed, and that zero is a real measurement
      return Optional
          .of(((oldest == null) || (oldest.getCreatedAt() == null))
              ? Duration.ZERO
              : PhaseTwoOutbox.waitedSince(oldest.getCreatedAt()));
    } catch (final RuntimeException e) {
      // a metric must never be the reason an application fails
      log.debug("Could not read the oldest pending entry of the MongoDB phase-two outbox", e);
      return Optional.empty();
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    // check first for an operation which still waits for its dispatch. Within a MongoDB
    // transaction, a duplicate-key error would abort the whole transaction, including the
    // aggregate. So a read finds the common duplicate case. The unique index stays the
    // safety net for duplicates at the same time. There the losing transaction aborts.
    // That is acceptable, because the operation was a duplicate anyway
    final var idempotencyKey = call.idempotencyKey().orElse(null);
    final var waiting = idempotencyKey == null
        ? null
        : mongoTemplate
            .findOne(
                Query.query(Criteria.where("dedupKey").is(idempotencyKey)),
                PhaseTwoOutboxEntry.class,
                collection);

    final var now = Instant.now();
    final var entryId = UUID.randomUUID().toString();
    // what the unique index sees. An operation which must not be deduplicated is
    // deduplicated against itself. So every entry has the field, and the index needs no
    // partial filter. A second entry beside one which a dispatch already took does the
    // same, because the key belongs to the entry on its way
    var dedupKey = idempotencyKey == null ? entryId : idempotencyKey;
    if (waiting != null) {
      if (!call.replacesWhatIsStillWaiting()) {
        logDiscardedSchedule(call);
        return false;
      }
      if (replacePendingEntry(waiting, call, now)) {
        triggerPollAfterCommit();
        return true;
      }
      // a poller claimed the entry between the read and the update. It runs to its end,
      // and this call becomes an entry of its own
      logSecondEntryBesideAClaimedOne(call);
      dedupKey = entryId;
    }
    final var entry = new PhaseTwoOutboxEntry(
        entryId, call.workflowModuleId(), call.bpmnProcessId(), call.operation(), call
            .workflowAggregateId(), call.adapterId(), call
                .args(), idempotencyKey, dedupKey, PhaseTwoOutboxEntry.STATUS_OPEN, now, 0, now, null, null, null, null);

    try {
      mongoTemplate.insert(entry, collection);
    } catch (DuplicateKeyException e) {
      logDiscardedSchedule(call);
      return false;
    }

    // the entry is in, so the bytes it names may follow. They go through the same
    // template, and only now, because a schedule discarded as a duplicate must leave
    // nothing behind
    if (call.hasPayload()) {
      dispatcher.getPayloadStore().write(call);
    }

    triggerPollAfterCommit();

    return true;

  }

  /**
   * A call which replaces goes the same way as any other. This is the one method which
   * says so. The mark travels in the call, so {@link #schedule(PhaseTwoCall)} reads it
   * where the entry is written.
   */
  @Override
  public boolean scheduleReplacingWhatIsStillWaiting(
      final PhaseTwoCall call) {

    return schedule(call.replacingWhatIsStillWaiting());

  }

  /**
   * Puts the younger call into the document of the waiting entry, including its payload.
   * Then it removes the payload the replaced entry named.
   * <p>
   * The attempts and the lease decide together. No attempt of this entry has ended, and
   * nobody is dispatching it right now. So no dispatch has read it, and none holds its
   * payload. The update carries both conditions. That makes it the same atomic claim the
   * dispatcher uses. If a poller wins the document, the update matches nothing. Both
   * halves are needed, because the attempts are written when an attempt ends. A dispatch
   * which is on its way still shows zero attempts, and only the lease names it.
   * <p>
   * Where a MongoDB transaction covers the writes, they commit together, and a rollback
   * takes them all. Without a transaction, they are three separate writes. That is the
   * window which the javadoc of this store calls best-effort.
   *
   * @return Whether the entry was replaced. <code>false</code> where a poller claimed it
   *         in the meantime. Then the call becomes an entry of its own
   */
  private boolean replacePendingEntry(
      final PhaseTwoOutboxEntry waiting,
      final PhaseTwoCall call,
      final Instant now) {

    if (waiting.getAttempts() > 0) {
      return false;
    }
    final var replaced = mongoTemplate
        .updateFirst(
            Query
                .query(Criteria
                    .where("_id")
                    .is(waiting.getId())
                    .and("attempts")
                    .is(0)
                    .orOperator(
                        Criteria.where("leasedUntil").is(null),
                        Criteria.where("leasedUntil").lte(now))),
            new Update()
                .set("operation", call.operation())
                .set("aggregateId", call.workflowAggregateId())
                .set("adapterId", call.adapterId())
                .set("args", call.args())
                .set("createdAt", now)
                .set("nextAttemptAt", now),
            collection)
        .getModifiedCount() == 1;
    if (!replaced) {
      return false;
    }
    if (call.hasPayload()) {
      dispatcher.getPayloadStore().write(call);
    }
    final var replacedReference = waiting.getArgs() == null
        ? null
        : waiting.getArgs().get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    if (replacedReference != null) {
      dispatcher.getPayloadStore().remove(replacedReference);
    }
    logReplacedEntry(call);
    return true;

  }

  /**
   * Dispatches the entry right after the transaction was committed. The dispatcher's
   * poller covers recovery after a crash. It wakes at the moment the earliest entry it
   * still owes becomes due.
   */
  private void triggerPollAfterCommit() {

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          dispatcher.triggerPoll();
        }
      });
    } else {
      dispatcher.triggerPoll();
    }

  }

  /**
   * A younger call took the place of the entry which was waiting. It is at DEBUG for the
   * same reason as a discard. It is what the caller asked for, and under a backlog it
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
   * twice. That is worth a line, because an application which counts its reports finds the
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
   * The technical half of a discarded schedule. There are two possible causes: a
   * redelivered dispatch, or an operation which lost against one still waiting. The store
   * cannot tell which. So the core reports it to the caller, and this line stays at DEBUG.
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
