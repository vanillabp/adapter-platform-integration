package io.vanillabp.integration.adapter.spi;

/**
 * Transaction outbox used to reliably execute the second phase of two-phase committed
 * BPMS calls (e.g. {@link MigratableProcessService#startWorkflowPhaseTwo(Object)})
 * after the local transaction which persisted the workflow aggregate was committed.
 * <p>
 * For every method of <code>io.vanillabp.spi.process.ProcessService</code> requiring a
 * two-phase commit there is a corresponding <code>schedule*</code> method in this
 * interface, dispatched to the corresponding method of the platform's
 * {@link PhaseTwoDispatch} bean. Which BPMS adapter is used is <i>not</i> part of the
 * scheduled call: it is determined at dispatch time (see
 * {@link ProcessServicePhaseTwo}).
 * <p>
 * Implementations are provided by the platform integrations (e.g. based on JDBC, JPA
 * or MongoDB) or by the business application itself, since the platform-neutral core
 * must not depend on any particular persistence technology.
 * <p>
 * <strong>Scheduling contract:</strong> The <code>schedule*</code> methods MUST be
 * invoked within the still-running local transaction that persists the workflow
 * aggregate, and the implementation MUST enlist the outbox entry in exactly that
 * transaction: the entry becomes visible if and only if the transaction commits. This
 * guarantees atomicity of "aggregate persisted" and "phase two will run" - preventing
 * ghost workflows in the BPMS as well as aggregates without workflows.
 * <p>
 * <strong>Recovery contract:</strong> Implementations must dispatch every
 * committed-but-unprocessed entry to the {@link PhaseTwoDispatch} method
 * corresponding to the scheduled operation
 * <ul>
 *   <li>right after the local transaction was committed and</li>
 *   <li>after an application restart (crash recovery), by polling for left-over
 *       entries.</li>
 * </ul>
 * On failed dispatch the entry has to be retried with a backoff. Entries must be
 * removed (or marked as processed) only <i>after</i> a successful dispatch. As a
 * consequence a dispatch may be repeated after a crash (at-least-once semantics) -
 * see the idempotency contract of
 * {@link MigratableProcessService#startWorkflowPhaseTwo(Object)}: the triple
 * <code>workflowModuleId + bpmnProcessId + workflowAggregateId</code> is the
 * idempotency key.
 */
public interface PhaseTwoOutbox {

  /**
   * Schedule phase two of starting a workflow, dispatched to
   * {@link PhaseTwoDispatch#startWorkflowPhaseTwo(String, String, Object)}. MUST be
   * invoked within the still-running local transaction that persists the workflow
   * aggregate, and MUST enlist in that transaction (entry becomes visible if and only
   * if the transaction commits).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param workflowAggregateId The ID of the workflow aggregate persisted in the local transaction
   */
  void scheduleStartWorkflow(
      String workflowModuleId,
      String bpmnProcessId,
      Object workflowAggregateId);

}
