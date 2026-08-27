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
    if ((idempotencyKey != null) && mongoTemplate.exists(
        Query.query(Criteria.where("dedupKey").is(idempotencyKey)),
        collection)) {
      logDiscardedSchedule(call);
      return false;
    }

    final var now = Instant.now();
    final var entryId = UUID.randomUUID().toString();
    final var entry = new PhaseTwoOutboxEntry(
        entryId, call.workflowModuleId(), call.bpmnProcessId(), call.operation(), call
            .workflowAggregateId(), call.adapterId(), call
                .args(), idempotencyKey,
        // an operation which must not be deduplicated dedupes against itself, which
        // keeps the field free of nulls a database might treat as equal
        idempotencyKey == null ? entryId : idempotencyKey, PhaseTwoOutboxEntry.STATUS_OPEN, now, 0, now, null);

    try {
      mongoTemplate.insert(entry, collection);
    } catch (DuplicateKeyException e) {
      logDiscardedSchedule(call);
      return false;
    }

    // dispatch the entry right after the transaction was committed; recovery after a
    // crash is covered by the dispatcher's fixed-delay poller
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

    return true;

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
