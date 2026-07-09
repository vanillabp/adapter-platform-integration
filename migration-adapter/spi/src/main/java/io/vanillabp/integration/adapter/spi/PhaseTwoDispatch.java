package io.vanillabp.integration.adapter.spi;

/**
 * The dispatch target of {@link PhaseTwoOutbox} implementations: whenever a scheduled
 * phase-two call becomes due (right after the scheduling transaction was committed or
 * during crash recovery), the outbox calls the method corresponding to the scheduled
 * operation.
 * <p>
 * Implemented once per platform integration: the implementation looks up the
 * {@link ProcessServicePhaseTwo} bean responsible for the workflow module and BPMN
 * process given and calls its corresponding phase-two method - so the call ends up in
 * the same (logical) process-service bean which scheduled it, where the adapter to be
 * used is determined (see {@link ProcessServicePhaseTwo}).
 * <p>
 * <strong>Aggregate-ID conversion:</strong> Outbox implementations may have to
 * serialize the workflow aggregate's ID (e.g. as a string) to store the scheduled
 * call. Therefore implementations of this interface must accept the aggregate ID in
 * serialized form ({@link String}) as well and convert it back to the aggregate's ID
 * type (determined via {@link ProcessServicePhaseTwo#getWorkflowAggregateClass()})
 * before passing it on.
 */
public interface PhaseTwoDispatch {

  /**
   * Dispatches phase two of starting a workflow to
   * {@link ProcessServicePhaseTwo#startWorkflowPhaseTwo(Object)}.
   * <p>
   * <strong>Idempotency contract:</strong> Since {@link PhaseTwoOutbox}
   * implementations provide at-least-once semantics, this method may be called
   * repeatedly for the same parameters (e.g. after a crash between dispatching the
   * call and marking the outbox entry as processed). The triple
   * <code>workflowModuleId + bpmnProcessId + workflowAggregateId</code> is the
   * idempotency key - see {@link MigratableProcessService#startWorkflowPhaseTwo(Object)}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param workflowAggregateId The ID of the workflow aggregate (possibly in serialized form)
   */
  void startWorkflowPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      Object workflowAggregateId);

}
