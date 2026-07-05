package io.vanillabp.integration.adapter.spi;

/**
 * This interface collects all methods necessary for two phase committed
 * BPMS calls. It may be implemented next to BPMS adapters by concrete platform integrations
 * components (beans) to establish a runtime context (bean context) for the second phase.
 * <p>
 * Calls to this interface are dispatched by a {@link PhaseTwoOutbox} implementation.
 */
public interface MigratableProcessServicePhaseTwo {

  /**
   * Has to call {@link MigratableProcessService#startWorkflowPhaseTwo(Object)}.
   * <p>
   * <strong>Idempotency contract:</strong> Since {@link PhaseTwoOutbox}
   * implementations provide at-least-once semantics, this method may be called
   * repeatedly for the same parameters (e.g. after a crash between dispatching the
   * call and marking the outbox entry as processed). The triple
   * <code>workflowModuleId + bpmnProcessId + workflowAggregateId</code> is the
   * idempotency key - see {@link MigratableProcessService#startWorkflowPhaseTwo(Object)}.
   * <p>
   * <strong>Aggregate-ID conversion:</strong> Outbox implementations may have to
   * serialize the workflow aggregate's ID (e.g. as a string) to store the scheduled
   * call. Therefore implementations of this method must accept the aggregate ID in
   * serialized form ({@link String}) as well and convert it back to the aggregate's
   * ID type before calling the adapter.
   *
   * @param workflowModuleId The ID of the workflow module the service belongs to
   * @param bpmnProcessId The BPMN process ID the service is for
   * @param adapterId The ID of the adapter this workflow belongs to
   * @param workflowAggregateId The ID of the workflow aggregate (possibly in serialized form)
   */
  void startWorkflowPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId,
      Object workflowAggregateId);

}
