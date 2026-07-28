package io.vanillabp.integration.outbox.gruelbox;

import io.vanillabp.integration.adapter.spi.PhaseTwoCall;

/**
 * The invocation scheduled through the gruelbox transaction outbox, carrying the
 * fields of a {@link PhaseTwoCall}. A dedicated interface is required because
 * gruelbox's <code>DefaultInvocationSerializer</code> only supports a whitelist of
 * parameter types - the call is flattened into {@link String} parameters and rebuilt
 * at dispatch time. Being an interface, it can be proxied by a plain JDK proxy (no
 * ByteBuddy required).
 * <p>
 * <strong>Deviation:</strong> the {@link PhaseTwoCall#args()} map is not transported -
 * it is empty for all operations existing today. Once an operation with arguments is
 * introduced, flatten its arguments into additional {@link String} parameters here.
 */
public interface GruelboxPhaseTwoDispatch {

  /**
   * Dispatches a phase-two call (called by the outbox after the scheduling
   * transaction was committed) by rebuilding the {@link PhaseTwoCall} and routing it
   * through the core's
   * {@link io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter}.
   *
   * @param operation The name of the scheduled {@link
   *        io.vanillabp.integration.adapter.spi.PhaseTwoOperation}
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The ID of the workflow aggregate in serialized form
   * @param adapterId The ID of the elected BPMS adapter (may be <code>null</code>)
   */
  void dispatch(
      String operation,
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId,
      String adapterId);

}
