package io.vanillabp.integration.extension.spi.runtime;

import java.time.Instant;
import java.util.Objects;

/**
 * An adapter refused a call or let an outbox entry wait, because the workflow is suspended in
 * its BPMS. The core hands this to every extension which matches the adapter, through
 * {@link io.vanillabp.integration.extension.spi.ExtensionWiringService#workflowFoundSuspended}.
 * <p>
 * It says when the adapter FOUND the suspension, not when somebody suspended the workflow. Who
 * did it and when is not part of it. An adapter does not know that without asking its BPMS once
 * more, and some BPMS do not know the time at all. An extension which needs it asks its own BPMS.
 *
 * @param adapterId The configured adapter id which found the suspension
 * @param workflowModuleId The workflow module of the call
 * @param bpmnProcessId The BPMN process id as the application wrote it, not scoped. May be
 *          <code>null</code> where the adapter did not know it
 * @param workflowAggregateId The id of the workflow aggregate of the call. It saves an
 *          extension a search for the aggregate. May be <code>null</code> where the adapter did
 *          not know it
 * @param workflowId The BPMS' own id of the suspended workflow, for example the process instance
 *          key on Camunda 8 or the process instance id on Camunda 7. It means the same as
 *          {@link io.vanillabp.integration.extension.spi.election.WorkflowLocation#workflowId()}
 * @param taskId The task of the refused call, or <code>null</code> where no task was concerned
 * @param foundAt When the adapter found the suspension
 * @param phase Whether the application got a refusal, or an outbox entry waits
 */
public record SuspendedWorkflowFound(
                                     String adapterId,
                                     String workflowModuleId,
                                     String bpmnProcessId,
                                     String workflowAggregateId,
                                     String workflowId,
                                     String taskId,
                                     Instant foundAt,
                                     Phase phase) {

  /**
   * Where the adapter found the suspension. It lets an extension tell the first sighting from
   * the repetitions of a waiting outbox entry.
   */
  public enum Phase {

    /**
     * Phase one: the application called, and the adapter refused the call with a
     * <code>WorkflowSuspendedException</code>. This happens once per call.
     */
    CALL,

    /**
     * Phase two: an outbox entry of an accepted call found the workflow suspended. The entry
     * waits and tries again later, so the same workflow is reported again on every attempt.
     */
    WAITING

  }

  /**
   * Checks the values every extension relies on.
   *
   * @throws NullPointerException If the adapter id, the workflow module id, the workflow id,
   *           the time or the phase is missing
   */
  public SuspendedWorkflowFound {

    Objects.requireNonNull(adapterId, "adapterId");
    Objects.requireNonNull(workflowModuleId, "workflowModuleId");
    Objects.requireNonNull(workflowId, "workflowId");
    Objects.requireNonNull(foundAt, "foundAt");
    Objects.requireNonNull(phase, "phase");

  }

}
