package io.vanillabp.integration.spi;

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
 * redelivering a task later than that runs the business code again; a task open for
 * longer than the retention is a workflow waiting for something, not a delivery in
 * flight.
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

}
