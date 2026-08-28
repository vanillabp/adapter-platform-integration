package io.vanillabp.integration.runtime.support;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import jakarta.enterprise.inject.Instance;

/**
 * Collects what this platform hands to an adapter, so that a producer asks for the set
 * instead of remembering the individual beans.
 * <p>
 * The Spring Boot integration has the same helper in
 * {@code AdapterBeanRegistrarSupport.collaborators}, and an adapter supporting both
 * platforms ends up with the same object either way - which is the point: what a
 * complete registration is has to be one answer, not one per platform.
 *
 * @see AdapterCollaborators
 */
public final class AdapterCollaboratorsSupport {

  private AdapterCollaboratorsSupport() {
    // static helper
  }

  /**
   * @param adapterId The adapter id the producer is building for
   * @param workflowTaskWiring What the adapter asks while it reads a BPMN file
   * @param workflowTaskInvoker Where a delivered task goes
   * @param scoping How the adapter avoids a name clash between workflow modules
   * @param workflowAggregateSync Which values of a workflow aggregate the BPMS may see
   * @param preCommitRegistrar Where work is hung which has to run before the caller's
   *                           transaction commits
   * @param workflowEndedInvoker Where a workflow's end is reported - an application
   *                             without such a method has no bean for it
   * @param bpmsInitiatedStartInvoker Where a workflow the BPMS started by itself is
   *                                  reported, likewise
   * @return The collaborators, complete
   */
  public static AdapterCollaborators collaborators(
      final String adapterId,
      final WorkflowTaskWiring workflowTaskWiring,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final NameClashAvoidanceSupport scoping,
      final WorkflowAggregateSync workflowAggregateSync,
      final PreCommitRegistrar preCommitRegistrar,
      final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
      final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker) {

    return AdapterCollaborators
        .forAdapter(adapterId)
        .workflowTaskWiring(workflowTaskWiring)
        .workflowTaskInvoker(workflowTaskInvoker)
        .scoping(scoping)
        .workflowAggregateSync(workflowAggregateSync)
        .preCommitRegistrar(preCommitRegistrar)
        .workflowEndedInvoker(resolved(workflowEndedInvoker))
        .bpmsInitiatedStartInvoker(resolved(bpmsInitiatedStartInvoker))
        .build();

  }

  /**
   * An {@code Instance} of a bean an application may not have: asking it for the bean
   * throws where the Spring side answers with an empty provider, so the absence is
   * turned into the null the builder expects.
   */
  private static <T> T resolved(
      final Instance<T> instance) {

    return (instance != null) && instance.isResolvable()
        ? instance.get()
        : null;

  }

}
