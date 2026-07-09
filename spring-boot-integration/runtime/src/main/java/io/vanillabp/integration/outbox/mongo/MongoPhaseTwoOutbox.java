package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import lombok.RequiredArgsConstructor;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * MongoDB for aggregate persistence (gruelbox is JDBC-only). The outbox entry is
 * written via {@link MongoTemplate} which participates in the currently running
 * Spring-managed MongoDB transaction. The scheduled operation is stored as a
 * discriminator with each entry (see {@link #OPERATION_START_WORKFLOW}), so the
 * dispatcher calls the corresponding
 * {@link io.vanillabp.integration.adapter.spi.PhaseTwoDispatch} method.
 * <p>
 * <strong>Note:</strong> MongoDB transactions require a replica set. Without one (no
 * <code>MongoTransactionManager</code> or standalone server) the entry is written
 * immediately and dispatching is best-effort: a crash between persisting the aggregate
 * and writing the entry may lose the phase-two call, and a rollback of the aggregate
 * does not remove an already written entry.
 */
@RequiredArgsConstructor
public class MongoPhaseTwoOutbox implements PhaseTwoOutbox {

  /**
   * The collection used to store outbox entries.
   */
  public static final String COLLECTION = "vanillabp-phase-two-outbox";

  /**
   * Operation discriminator of entries scheduled via
   * {@link #scheduleStartWorkflow(String, String, Object)}.
   */
  public static final String OPERATION_START_WORKFLOW = "START_WORKFLOW";

  private final MongoTemplate mongoTemplate;

  private final MongoPhaseTwoOutboxDispatcher dispatcher;

  @Override
  public void scheduleStartWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    final var now = Instant.now();
    final var entry = new PhaseTwoOutboxEntry(
        UUID.randomUUID()
            .toString(), workflowModuleId, bpmnProcessId, OPERATION_START_WORKFLOW, workflowAggregateId == null ? null
                : workflowAggregateId.toString(), workflowAggregateId == null ? null
                    : workflowAggregateId.getClass().getName(), now, 0, now);

    mongoTemplate.insert(entry, COLLECTION);

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

  }

}
