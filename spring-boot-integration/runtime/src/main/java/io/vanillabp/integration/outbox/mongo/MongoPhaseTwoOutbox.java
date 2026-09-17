package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * MongoDB for aggregate persistence (gruelbox is JDBC-only). The outbox entry is
 * written via {@link MongoTemplate} which participates in the currently running
 * Spring-managed MongoDB transaction. The entry persists all fields of the
 * {@link PhaseTwoCall} including the operation discriminator and the elected adapter
 * ID; deduplication is enforced by a unique index on the entry's <code>dedupKey</code>
 * and therefore spans the entries still waiting for their dispatch, as the contract of
 * {@link PhaseTwoOutbox} demands: that field carries the idempotency key while the
 * entry waits and the entry's own id once the dispatcher marked it DONE, while
 * <code>idempotencyKey</code> keeps the key readable for support. The at-least-once
 * guarantee is unaffected, because a redispatch reads the same document - it is the
 * entry's status and attempt count which carry it, never the key.
 * A duplicate schedule is detected by a pre-check read and turned into the
 * contract's no-op (<code>false</code>) - within a MongoDB transaction a
 * {@link DuplicateKeyException} would abort the whole transaction, so the unique
 * index only remains the backstop for concurrent duplicates.
 * <p>
 * <strong>A younger call may take the waiting entry's place</strong> instead of being
 * discarded against it, where it says so
 * ({@link PhaseTwoCall#replacingWhatIsStillWaiting()}). The document keeps its id and
 * its key and gets everything the dispatch reads, and the payload of the entry it
 * replaced is removed with it. What decides is <code>attempts</code>: the dispatcher
 * counts the attempt when it claims an entry, so a zero there means no dispatch has
 * read this entry and none is holding its payload. The update carries that condition,
 * which makes it the same atomic claim - if a poller wins the document, the update
 * matches nothing and the call becomes an entry of its own, with <code>dedupKey</code>
 * set to its own id because the key belongs to the entry on its way.
 * <p>
 * <strong>Note:</strong> MongoDB transactions require a replica set. Without one (no
 * <code>MongoTransactionManager</code> or standalone server) the entry is written
 * immediately and dispatching is best-effort: a crash between persisting the aggregate
 * and writing the entry may lose the phase-two call, and a rollback of the aggregate
 * does not remove an already written entry.
 */
@RequiredArgsConstructor
@Slf4j
public class MongoPhaseTwoOutbox implements PhaseTwoOutbox {

  private final MongoTemplate mongoTemplate;

  private final MongoPhaseTwoOutboxDispatcher dispatcher;

  /**
   * The collection used to store outbox entries
   * (<code>vanillabp.outbox.mongo.collection</code>). Every outbox instance needs
   * its own collection - two dispatchers polling the same collection would compete
   * and double-dispatch.
   */
  private final String collection;

  /**
   * The adapter ids the OPEN entries of one BPMN process are waiting for: an
   * id which is not configured any more means that it was renamed or removed too early,
   * and both leave the workflow of a START entry unstarted.
   */
  @Override
  public java.util.Set<String> adapterIdsOfPendingCalls(
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
    return new java.util.LinkedHashSet<>(
        mongoTemplate.findDistinct(query, "adapterId", collection, String.class));

  }

  /**
   * Counts the entries waiting for their dispatch - a single count over the same
   * collection the dispatcher polls.
   */
  @Override
  public java.util.OptionalLong pendingCalls() {

    try {
      return java.util.OptionalLong
          .of(mongoTemplate
              .count(
                  Query.query(Criteria.where("status").is(PhaseTwoOutboxEntry.STATUS_OPEN)),
                  collection));
    } catch (final RuntimeException e) {
      // a metric must never be the reason an application fails
      log.debug("Could not count the pending entries of the MongoDB phase-two outbox", e);
      return java.util.OptionalLong.empty();
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    // pre-check for an operation still waiting for its dispatch: within a MongoDB
    // transaction a duplicate-key error would abort the whole transaction (including
    // the aggregate), so the common duplicate case is detected by a read; the unique
    // index remains the backstop for concurrent duplicates (there the losing
    // transaction aborts - acceptable, since the operation was a duplicate anyway)
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
    // a second entry of a key an entry on its way still holds takes no part in the
    // deduplication of that key, the way a keyless entry does not - see the contract
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
      // a poller claimed the entry between the read and the update: it runs to its end
      // and this call becomes an entry of its own
      logSecondEntryBesideAClaimedOne(call);
      dedupKey = entryId;
    }
    final var entry = new PhaseTwoOutboxEntry(
        entryId, call.workflowModuleId(), call.bpmnProcessId(), call.operation(), call
            .workflowAggregateId(), call.adapterId(), call
                .args(), idempotencyKey,
        // an operation which must not be deduplicated dedupes against itself, which
        // keeps the field free of nulls a database might treat as equal
        dedupKey, PhaseTwoOutboxEntry.STATUS_OPEN, now, 0, now, null);

    try {
      mongoTemplate.insert(entry, collection);
    } catch (DuplicateKeyException e) {
      logDiscardedSchedule(call);
      return false;
    }

    // the entry is in, so the bytes it names may follow - through the same template,
    // and only now, because a schedule discarded as a duplicate must leave nothing
    // behind
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
   * Puts the younger call into the document of the waiting entry, its payload
   * included, and removes the payload the replaced entry named.
   * <p>
   * What decides is the number of attempts: the dispatcher counts one when it claims an
   * entry, so a zero means no dispatch has read this entry and none is holding its
   * payload. The update carries that condition, which makes it the same atomic claim
   * the dispatcher uses - if a poller wins the document, the update matches nothing.
   * <p>
   * Where a MongoDB transaction covers the writes they commit together and a rollback
   * takes them all. Without one they are three separate writes, the window this store's
   * javadoc calls best-effort.
   *
   * @return Whether the entry was replaced - <code>false</code> where a poller claimed
   *         it in the meantime, which makes the call an entry of its own
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
            Query.query(Criteria.where("_id").is(waiting.getId()).and("attempts").is(0)),
            new org.springframework.data.mongodb.core.query.Update()
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
   * Dispatches the entry right after the transaction was committed; recovery after a
   * crash is covered by the dispatcher's fixed-delay poller.
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
