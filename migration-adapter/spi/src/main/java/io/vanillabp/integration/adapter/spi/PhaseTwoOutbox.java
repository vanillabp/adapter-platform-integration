package io.vanillabp.integration.adapter.spi;

/**
 * Transaction outbox used to reliably execute the second phase of a two-phase
 * workflow start (see {@link MigratableProcessService#startWorkflowPhaseTwo(Object)})
 * after the local transaction which persisted the workflow aggregate was committed.
 * <p>
 * Implementations are provided by the platform integrations (e.g. based on JDBC, JPA
 * or MongoDB) or by the business application itself, since the platform-neutral core
 * must not depend on any particular persistence technology.
 * <p>
 * <strong>Scheduling contract:</strong> {@link #schedule(String, String, String, Object)}
 * MUST be invoked within the still-running local transaction that persists the
 * workflow aggregate, and the implementation MUST enlist the outbox entry in exactly
 * that transaction: the entry becomes visible if and only if the transaction commits.
 * This guarantees atomicity of "aggregate persisted" and "phase two will run" -
 * preventing ghost workflows in the BPMS as well as aggregates without workflows.
 * <p>
 * <strong>Recovery contract:</strong> Implementations must dispatch every
 * committed-but-unprocessed entry to
 * {@link MigratableProcessServicePhaseTwo#startWorkflowPhaseTwo(String, String, String, Object)}
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
   * Schedule the phase-two call. MUST be invoked within the still-running local
   * transaction that persists the workflow aggregate, and MUST enlist in that
   * transaction (entry becomes visible if and only if the transaction commits).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param adapterId The ID of the adapter the workflow was started with in phase one
   * @param workflowAggregateId The ID of the workflow aggregate persisted in the local transaction
   */
  void schedule(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId,
      Object workflowAggregateId);

}
