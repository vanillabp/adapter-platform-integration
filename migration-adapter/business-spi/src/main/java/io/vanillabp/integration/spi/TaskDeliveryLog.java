package io.vanillabp.integration.spi;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable memory of the task deliveries VanillaBP already processed - the inbound
 * counterpart of the {@link PhaseTwoOutbox}. A remote BPMS delivers a task AT LEAST
 * ONCE: it repeats a delivery whenever it did not learn the result, e.g. after a
 * crash between the commit of the local transaction and the report to the BPMS.
 * Without a memory the <code>&#64;WorkflowTask</code> method runs a second time; with
 * one the core skips the business code and reports the recorded outcome again.
 * <p>
 * <strong>Recording contract:</strong> {@link #record(TaskDelivery)} MUST be invoked
 * within the still-running transaction which persists the workflow aggregate, and the
 * implementation MUST enlist the record in exactly that transaction: the record
 * becomes visible if and only if the transaction commits. Work without a record would
 * run the business code twice, a record without the work would skip it although
 * nothing happened - both are worse than no deduplication at all. A rolled-back
 * delivery therefore leaves NO record and is processed again, which is what makes the
 * BPMS' retry work.
 * <p>
 * <strong>Uniqueness:</strong> the {@link TaskDelivery#deliveryKey()} is unique in the
 * store, enforced by the store's own mechanism (unique index/constraint). A second
 * {@link #record(TaskDelivery)} of the same key is a no-op returning
 * <code>false</code> - two nodes processing the same delivery concurrently (both
 * having read no record) end up with one record, and the loser's transaction fails
 * respectively is ignored.
 * <p>
 * <strong>Retention:</strong> records are deleted asynchronously once
 * <code>vanillabp.outbox.retention</code> passed (default 7 days) - the same
 * retention the outbox uses, since both keep a deduplication window open. A BPMS
 * redelivering a task later than that runs the business code again. The period counts
 * from the last redelivery the record answered ({@link #stillOpen(String)}), so a task
 * which stays open keeps the record answering it and the clock starts once nobody hands
 * that task out any more.
 * <p>
 * <strong>Release at the end of a workflow:</strong> where
 * <code>vanillabp.delivery.release-on-workflow-end</code> is switched on, the records of
 * a workflow are deleted the moment it ends - nothing of an ended instance can be
 * redelivered, so the clock does not have to decide it any more (see
 * {@link #releaseRecordsOf(String, String, String, Instant)}). A store which does not
 * implement the release keeps its records until the retention passed, and the startup
 * says so where the release was asked for.
 * <p>
 * Implementations are provided by the platform integrations (JDBC/JPA and MongoDB) or
 * by the application itself, since the platform-neutral core must not depend on any
 * particular persistence technology. Which log serves a workflow aggregate is decided
 * per aggregate - see {@link TaskDeliveryLogAware}.
 */
public interface TaskDeliveryLog {

  /**
   * The record of the given delivery, if VanillaBP processed it before. Read within
   * the transaction the delivery is processed in.
   *
   * @param deliveryKey The delivery's identity (see
   *          {@link TaskDelivery#deliveryKey()})
   * @return The record or {@link Optional#empty()} if this delivery was not
   *         processed (or its record was cleaned up after the retention period)
   */
  Optional<TaskDelivery> recordedDelivery(
      String deliveryKey);

  /**
   * Records a processed delivery. MUST be invoked within the still-running
   * transaction that persists the workflow aggregate, and MUST enlist in that
   * transaction.
   *
   * @param delivery What was processed, and with which outcome
   * @return <code>true</code> if the record was written, <code>false</code> if a
   *         record of the same {@link TaskDelivery#deliveryKey()} already existed
   *         (no-op)
   */
  boolean record(
      TaskDelivery delivery);

  /**
   * Reports that the BPMS delivered a task again whose record says the task is still
   * open, so this record is still in use and has to outlive the retention. Called by the
   * core within the transaction of that redelivery, for every delivery answered with
   * <code>COMPLETION_PENDING</code>.
   * <p>
   * A store deleting by age keeps a SECOND timestamp per record for it: the retention
   * counts from the last redelivery, while {@link TaskDelivery#recordedAt()} keeps
   * meaning the moment the handler ran - which is what the age of an open task is
   * measured from (<code>vanillabp.delivery.max-task-age</code>). Refreshing
   * <code>recordedAt</code> instead would erase that age, and keeping every pending
   * record forever would leave the record of a task nobody ever completes in the store
   * for good.
   * <p>
   * <strong>Not a write per call.</strong> An open task is redelivered as often as the
   * BPMS renews its lock, and the redelivery runs in the transaction of the workflow
   * aggregate. Implementations therefore remember the key and refresh their records in
   * bulk, out of band - the stores VanillaBP ships do it from the timer which already
   * runs the retention cleanup. Losing such a memory to a crash costs one interval of
   * refreshments and nothing more, because a record only expires when a whole retention
   * passes without a single one.
   * <p>
   * The default implementation does nothing, which keeps every store written before this
   * existed valid: its records expire as they always did.
   *
   * @param deliveryKey The identity of the redelivered delivery (see
   *          {@link TaskDelivery#deliveryKey()})
   */
  default void stillOpen(
      final String deliveryKey) {

  }

  /**
   * Deletes the records of ONE workflow, called when it ended and nothing of it can be
   * redelivered any more. Invoked within the transaction of that notification, so the
   * deletion commits with whatever else that transaction does.
   * <p>
   * The four arguments are the bound of the deletion, and each part of it matters: an
   * aggregate may carry more than one BPMN process, and it may outlive its workflow and
   * carry a SECOND one afterwards - whose records were written AFTER the notification
   * and must survive it.
   * <p>
   * The default implementation deletes nothing, which keeps every store written before
   * this existed valid: its records are cleaned up by the retention as they always
   * were. VanillaBP reports at startup that the release was asked for and cannot be
   * served, naming the store and the property.
   *
   * @param workflowModuleId The workflow module of the ended workflow
   * @param bpmnProcessId The BPMN process of the ended workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @param recordedBefore Only records written before this moment are deleted
   * @return The number of records deleted
   */
  default int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Instant recordedBefore) {

    return 0;

  }

}
