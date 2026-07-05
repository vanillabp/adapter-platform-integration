package io.vanillabp.integration.outbox.gruelbox;

import io.vanillabp.integration.adapter.spi.MigratableProcessServicePhaseTwo;

/**
 * The invocation scheduled through the gruelbox transaction outbox. A dedicated
 * interface (instead of {@link MigratableProcessServicePhaseTwo}) is required because
 * gruelbox's <code>DefaultInvocationSerializer</code> only supports a whitelist of
 * parameter types - the SPI's <code>Object</code>-typed workflow-aggregate ID cannot
 * be serialized, so it is passed as a {@link String} here. Being an interface, it can
 * be proxied by a plain JDK proxy (no ByteBuddy required).
 */
public interface GruelboxPhaseTwoDispatch {

  /**
   * Dispatches phase two of starting a workflow (called by the outbox after the
   * scheduling transaction was committed).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param adapterId The ID of the adapter the workflow was started with in phase one
   * @param workflowAggregateId The ID of the workflow aggregate as a string
   */
  void startWorkflowPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId,
      String workflowAggregateId);

}
