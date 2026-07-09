package io.vanillabp.integration.outbox.gruelbox;

import io.vanillabp.integration.adapter.spi.PhaseTwoDispatch;

/**
 * The invocation scheduled through the gruelbox transaction outbox, mirroring the
 * methods of {@link PhaseTwoDispatch}. A dedicated interface is required because
 * gruelbox's <code>DefaultInvocationSerializer</code> only supports a whitelist of
 * parameter types - the SPI's <code>Object</code>-typed workflow-aggregate ID cannot
 * be serialized, so it is passed as a {@link String} here. Being an interface, it can
 * be proxied by a plain JDK proxy (no ByteBuddy required).
 * <p>
 * There is one method per schedulable operation: which phase-two method is executed is
 * encoded in the scheduled method itself, so no operation discriminator has to be
 * stored.
 */
public interface GruelboxPhaseTwoDispatch {

  /**
   * Dispatches phase two of starting a workflow (called by the outbox after the
   * scheduling transaction was committed) to
   * {@link PhaseTwoDispatch#startWorkflowPhaseTwo(String, String, Object)}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param workflowAggregateId The ID of the workflow aggregate as a string
   */
  void startWorkflowPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId);

}
