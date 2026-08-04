package io.vanillabp.integration.spi;

/**
 * Transaction outbox used to reliably execute the second phase of two-phase committed
 * BPMS calls (e.g.
 * {@code MigratableProcessService#startWorkflowPhaseTwo})
 * after the local transaction which persisted the workflow aggregate was committed.
 * <p>
 * For every method of <code>io.vanillabp.spi.process.ProcessService</code> requiring a
 * two-phase commit there is a corresponding typed <code>schedule*</code> default
 * method building a {@link PhaseTwoCall} and delegating to the single abstract method
 * {@link #schedule(PhaseTwoCall)} - stores implement exactly one method. Dispatch
 * happens through the core's <code>PhaseTwoRouter</code> which routes the call to the
 * <code>MigrationProcessService</code> of the workflow module/BPMN process.
 * <p>
 * Implementations are provided by the platform integrations (e.g. based on JDBC, JPA
 * or MongoDB) or by the business application itself, since the platform-neutral core
 * must not depend on any particular persistence technology.
 * <p>
 * <strong>Scheduling contract:</strong> {@link #schedule(PhaseTwoCall)} MUST be
 * invoked within the still-running local transaction that persists the workflow
 * aggregate, and the implementation MUST enlist the outbox entry in exactly that
 * transaction: the entry becomes visible if and only if the transaction commits. This
 * guarantees atomicity of "aggregate persisted" and "phase two will run" - preventing
 * ghost workflows in the BPMS as well as aggregates without workflows.
 * <p>
 * <strong>Idempotency contract:</strong> Implementations MUST enforce uniqueness of
 * {@link PhaseTwoCall#idempotencyKey()} (where present) using the store's
 * unique-constraint mechanism (unique index/constraint on the persisted key). A
 * duplicate {@link #schedule(PhaseTwoCall)} is a no-op and returns
 * <code>false</code>. This is the storage-level enforcement of the documented
 * idempotency of two-phase operations (e.g. a workflow is started at most once per
 * aggregate).
 * <p>
 * <strong>Recovery contract:</strong> Implementations must dispatch every
 * committed-but-unprocessed entry
 * <ul>
 * <li>right after the local transaction was committed and</li>
 * <li>after an application restart (crash recovery), by polling for left-over
 * entries.</li>
 * </ul>
 * On failed dispatch the entry has to be retried with a backoff.
 * <p>
 * <strong>DONE instead of delete:</strong> A successful dispatch marks the entry as
 * DONE - it is NOT deleted immediately. Physical deletion happens asynchronously
 * after a configurable retention period (<code>vanillabp.outbox.*</code>, default 7
 * days). This keeps the deduplication window of the idempotency contract open beyond
 * dispatch.
 * <p>
 * <strong>At-least-once residual window:</strong> A crash between the remote BPMS
 * call and marking the entry DONE re-dispatches the entry on recovery. This residual
 * window is accepted (eventual consistency); adapters keep their operations
 * idempotent (see
 * {@code MigratableProcessService#startWorkflowPhaseTwo}).
 * <p>
 * TODO (story 25, fallback election): mitigate the residual window by probing
 * {@code MigratableProcessService#awarenessOfWorkflow} before re-dispatching
 * entries with <code>attempts &gt; 0</code> - on a hit mark DONE instead of
 * re-dispatching. Do not call awareness methods before that story lands.
 * <p>
 * <strong>Poison entries:</strong> Entries failing repeatedly are blocked after a
 * configurable number of attempts and left in the store as a monitorable trail - the
 * implementation logs an ERROR naming workflow module, BPMN process, aggregate ID and
 * operation. Dispatch failures caused by a BPMN process no longer being part of the
 * application, or by an adapter ID no longer being configured (stale entry after a
 * configuration change), yield guiding messages naming that case.
 */
public interface PhaseTwoOutbox {

  /**
   * Schedule the given phase-two call. MUST be invoked within the still-running
   * local transaction that persists the workflow aggregate, and MUST enlist in that
   * transaction (entry becomes visible if and only if the transaction commits).
   *
   * @param call The phase-two call to schedule
   * @return <code>true</code> if the call was scheduled, <code>false</code> if an
   *         entry with the same {@link PhaseTwoCall#idempotencyKey()} was already
   *         scheduled (no-op)
   */
  boolean schedule(
      PhaseTwoCall call);

  /**
   * Schedule phase two of starting a workflow. The adapter elected in phase one is
   * part of the scheduled call: phase two uses exactly this adapter instead of
   * re-electing one from the then-current priorities. Entries may become stale if
   * the adapter is removed from the configuration while the entry is still open -
   * this is accepted, the dispatch error message names that case.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param workflowAggregateId The ID of the workflow aggregate persisted in the
   *        local transaction
   * @param adapterId The ID of the BPMS adapter elected in phase one
   * @return <code>true</code> if scheduled, <code>false</code> if already scheduled
   *         for this aggregate (no-op)
   */
  default boolean scheduleStartWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String adapterId) {

    return schedule(
        new PhaseTwoCall(
            PhaseTwoOperation.START_WORKFLOW, workflowModuleId, bpmnProcessId, workflowAggregateId
                .toString(), adapterId, null));

  }

  /**
   * Schedule phase two of completing an asynchronous task. NO adapter ID is
   * persisted (unlike workflow starts): the executing adapter is elected at
   * dispatch time by probing the prioritized adapters - the BPMS holding the task
   * answers the probe.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the task to complete (as reported to the
   *        <code>&#64;TaskId</code> parameter)
   * @return <code>true</code> if scheduled, <code>false</code> if already scheduled
   *         for this task (no-op)
   */
  default boolean scheduleCompleteTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String taskId) {

    return schedule(
        new PhaseTwoCall(
            PhaseTwoOperation.COMPLETE_TASK, workflowModuleId, bpmnProcessId, workflowAggregateId
                .toString(), null, java.util.Map.of(PhaseTwoCall.ARG_TASK_ID, taskId)));

  }

  /**
   * Schedule phase two of canceling an asynchronous task by BPMN error. NO adapter
   * ID is persisted - see {@link #scheduleCompleteTask}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the task to cancel
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   * @return <code>true</code> if scheduled, <code>false</code> if already scheduled
   *         for this task (no-op)
   */
  default boolean scheduleCancelTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    return schedule(
        new PhaseTwoCall(
            PhaseTwoOperation.CANCEL_TASK, workflowModuleId, bpmnProcessId, workflowAggregateId
                .toString(), null, java.util.Map
                    .of(
                        PhaseTwoCall.ARG_TASK_ID, taskId, PhaseTwoCall.ARG_BPMN_ERROR_CODE,
                        bpmnErrorCode)));

  }

  /**
   * Schedule phase two of completing a USER task - same contract as
   * {@link #scheduleCompleteTask}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the user task to complete
   * @return <code>true</code> if scheduled, <code>false</code> if already scheduled
   *         for this task (no-op)
   */
  default boolean scheduleCompleteUserTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String taskId) {

    return schedule(
        new PhaseTwoCall(
            PhaseTwoOperation.COMPLETE_USER_TASK, workflowModuleId, bpmnProcessId, workflowAggregateId
                .toString(), null, java.util.Map.of(PhaseTwoCall.ARG_TASK_ID, taskId)));

  }

  /**
   * Schedule phase two of canceling a USER task by BPMN error - same contract as
   * {@link #scheduleCancelTask}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the user task to cancel
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   * @return <code>true</code> if scheduled, <code>false</code> if already scheduled
   *         for this task (no-op)
   */
  default boolean scheduleCancelUserTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    return schedule(
        new PhaseTwoCall(
            PhaseTwoOperation.CANCEL_USER_TASK, workflowModuleId, bpmnProcessId, workflowAggregateId
                .toString(), null, java.util.Map
                    .of(
                        PhaseTwoCall.ARG_TASK_ID, taskId, PhaseTwoCall.ARG_BPMN_ERROR_CODE,
                        bpmnErrorCode)));

  }

  /**
   * Schedule phase two of correlating a message. NO adapter ID is persisted - the
   * executing adapter is elected at dispatch time by probing (the BPMS holding
   * the workflow instance answers). WITHOUT a correlation id the entry carries NO
   * idempotency key - the same message may legitimately be correlated multiple
   * times (an at-least-once dispatch may then double-correlate).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param messageName The BPMN message name
   * @param correlationId The correlation id or <code>null</code>
   * @return <code>true</code> if scheduled, <code>false</code> if an entry with
   *         the same idempotency key was already scheduled (no-op; only possible
   *         WITH a correlation id)
   */
  default boolean scheduleCorrelateMessage(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    final var args = new java.util.LinkedHashMap<String, String>();
    args.put(PhaseTwoCall.ARG_MESSAGE_NAME, messageName);
    if (correlationId != null) {
      args.put(PhaseTwoCall.ARG_CORRELATION_ID, correlationId);
    }
    return schedule(
        new PhaseTwoCall(
            PhaseTwoOperation.CORRELATE_MESSAGE, workflowModuleId, bpmnProcessId, workflowAggregateId
                .toString(), null, args));

  }

  /**
   * Schedule phase two of starting a workflow BY MESSAGE. Start semantics: the
   * adapter elected in phase one is persisted and used in phase two, and a
   * workflow is started at most once per aggregate.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param messageName The BPMN message name of the message start event
   * @param adapterId The ID of the BPMS adapter elected in phase one
   * @return <code>true</code> if scheduled, <code>false</code> if already
   *         scheduled for this aggregate (no-op)
   */
  default boolean scheduleStartWorkflowByMessage(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String messageName,
      final String adapterId) {

    return schedule(
        new PhaseTwoCall(
            PhaseTwoOperation.START_WORKFLOW_BY_MESSAGE, workflowModuleId, bpmnProcessId, workflowAggregateId
                .toString(), adapterId, java.util.Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, messageName)));

  }

}
