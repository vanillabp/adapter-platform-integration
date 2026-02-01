package io.vanillabp.intergration.adapter.spi;

/**
 * This interface collects all methods necessary for two phase committed
 * BPMS calls. It may be implemented next to BPMS adapters by concrete platform integrations
 * components (beans) to establish a runtime context (bean context) for the second phase.
 */
public interface MigratableProcessServicePhaseTwo {

  /**
   * Has to call {@link MigratableProcessService#startWorkflowPhaseTwo(Object)}.
   *
   * @param workflowModuleId The ID of the workflow module the service belongs to
   * @param bpmnProcessId The BPMN process ID the service is for
   * @param adapterId The ID of the adapter this workflow belongs to
   * @param workflowAggregateId The ID of the workflow aggregate
   */
  void startWorkflowPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId,
      Object workflowAggregateId);

}
