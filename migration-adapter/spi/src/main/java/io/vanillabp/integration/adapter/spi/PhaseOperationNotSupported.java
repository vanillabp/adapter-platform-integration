package io.vanillabp.integration.adapter.spi;

import java.util.Map;

import io.vanillabp.integration.spi.PhaseOperation;

/**
 * Thrown where an application asks for an operation the elected adapter has no handler
 * for. Only an operation which is not
 * {@link PhaseOperation#requiredOfEveryAdapter()} can end up here - a required one is
 * refused while the application boots, naming the adapter, so nobody learns about it
 * from a workflow standing still.
 * <p>
 * The message says what the adapter cannot do, and the operation itself says what to do
 * instead ({@link PhaseOperation.Wording#remedyWhenUnsupported()}).
 */
public class PhaseOperationNotSupported extends UnsupportedOperationException {

  private static final long serialVersionUID = 1L;

  /**
   * @param adapterId The ID of the adapter which has no handler for the operation
   * @param operation The operation asked for
   * @param workflowModuleId The ID of the workflow module the call belongs to
   * @param bpmnProcessId The BPMN process ID the call belongs to
   * @param args The arguments of the call, used to name what was asked for
   */
  public PhaseOperationNotSupported(
      final String adapterId,
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, String> args) {

    super(
        """
            The VanillaBP adapter '%s' cannot serve %s of BPMN process '%s' (workflow module '%s'): \
            its BPMS has nothing like the operation '%s', or the adapter predates it.%s"""
            .formatted(
                adapterId,
                operation.describe(args),
                bpmnProcessId,
                workflowModuleId,
                operation.name(),
                operation.wording().remedyWhenUnsupported().isEmpty()
                    ? ""
                    : " "
                        + operation.wording().remedyWhenUnsupported()));

  }

}
