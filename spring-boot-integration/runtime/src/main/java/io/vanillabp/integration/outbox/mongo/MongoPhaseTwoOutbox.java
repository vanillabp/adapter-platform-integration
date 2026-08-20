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
 * ID; deduplication is enforced by a sparse unique index on the entry's idempotency
 * key. A duplicate schedule is detected by a pre-check read and turned into the
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

    // pre-check for an existing entry: within a MongoDB transaction a duplicate-key
    // error would abort the whole transaction (including the aggregate), so the
    // common duplicate case is detected by a read; the unique index remains the
    // backstop for concurrent duplicates (there the losing transaction aborts -
    // acceptable, since the operation was a duplicate anyway)
    final var idempotencyKey = call.idempotencyKey().orElse(null);
    if ((idempotencyKey != null) && mongoTemplate.exists(
        Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
        collection)) {
      log.debug(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "was already scheduled - skipping",
          call.operation(),
          call.bpmnProcessId(),
          call.workflowModuleId(),
          call.workflowAggregateId());
      return false;
    }

    final var now = Instant.now();
    final var entry = new PhaseTwoOutboxEntry(
        UUID.randomUUID()
            .toString(), call.workflowModuleId(), call.bpmnProcessId(), call.operation(), call
                .workflowAggregateId(), call.adapterId(), call
                    .args(), idempotencyKey, PhaseTwoOutboxEntry.STATUS_OPEN, now, 0, now, null);

    try {
      mongoTemplate.insert(entry, collection);
    } catch (DuplicateKeyException e) {
      log.debug(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "was already scheduled - skipping",
          call.operation(),
          call.bpmnProcessId(),
          call.workflowModuleId(),
          call.workflowAggregateId());
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

}
