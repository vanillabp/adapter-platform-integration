package io.vanillabp.integration.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of the second phase of a two-phase committed BPMS call,
 * scheduled via {@link PhaseTwoOutbox#schedule(PhaseTwoCall)} within the local
 * transaction and dispatched after that transaction was committed.
 * <p>
 * The workflow-aggregate ID is carried in its serialized {@link String} form - the
 * serialized form is the ONLY form in transport. Conversion back to the aggregate's
 * ID type happens exactly once, at dispatch time, by the core's router using a
 * converter registered by the platform integration.
 *
 * @param operation The operation to execute
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param workflowAggregateId The workflow-aggregate ID in serialized (String) form
 * @param adapterId The ID of the elected BPMS adapter - set for
 *        {@link PhaseTwoOperation#START_WORKFLOW} (the adapter elected in phase one
 *        is persisted and used in phase two), <code>null</code> for future probing
 *        operations which determine the adapter at dispatch time
 * @param args Additional operation-specific arguments (empty for
 *        {@link PhaseTwoOperation#START_WORKFLOW})
 */
public record PhaseTwoCall(
                           PhaseTwoOperation operation,
                           String workflowModuleId,
                           String bpmnProcessId,
                           String workflowAggregateId,
                           String adapterId,
                           Map<String, String> args) {

  public PhaseTwoCall {
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(workflowModuleId, "workflowModuleId must not be null");
    Objects.requireNonNull(bpmnProcessId, "bpmnProcessId must not be null");
    Objects.requireNonNull(workflowAggregateId, "workflowAggregateId must not be null");
    args = args == null ? Map.of() : Map.copyOf(args);
  }

  /**
   * The idempotency key of this call, derived per operation - see the derivation
   * rules documented on {@link PhaseTwoOperation}.
   *
   * @return The idempotency key or {@link Optional#empty()} if the operation must
   *         not be deduplicated
   */
  public Optional<String> idempotencyKey() {

    return operation.idempotencyKey(this);

  }

}
