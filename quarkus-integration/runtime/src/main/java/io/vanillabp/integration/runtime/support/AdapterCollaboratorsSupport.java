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
 * Collects what this platform hands to an adapter. So a producer asks for the whole set and
 * does not have to remember the single beans.
 * <p>
 * The Spring Boot integration has the same helper in
 * {@code AdapterBeanRegistrarSupport.collaborators}. An adapter which supports both platforms
 * gets the same object on either of them. That is the point: there is one answer to what a
 * complete registration is, not one per platform.
 *
 * @see AdapterCollaborators
 */
public final class AdapterCollaboratorsSupport {

  private AdapterCollaboratorsSupport() {
  }

  /**
   * Builds the collaborators of one adapter id. Every one of them is a parameter here. So a
   * producer which forgets one does not compile. That is why they are handed over in one object
   * (see decision 28 in the repository's DECISIONS.md).
   *
   * @param adapterId The adapter id the producer is building for
   * @param workflowTaskWiring What the adapter asks while it reads a BPMN file
   * @param workflowTaskInvoker Where a delivered task goes
   * @param scoping How the adapter avoids a name clash between workflow modules
   * @param workflowAggregateSync Which values of a workflow aggregate the BPMS may see
   * @param preCommitRegistrar Where to register work which has to run before the caller's
   *                           transaction commits
   * @param workflowEndedInvoker Where the end of a workflow is reported. An application
   *                             without such a method has no bean for it
   * @param bpmsInitiatedStartInvoker Where a workflow is reported which the BPMS started by
   *                                  itself. Here too, the bean may be missing
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
   * Resolves an {@code Instance} of a bean which an application may not have. Asking such an
   * instance for the bean throws, while the Spring side answers with an empty provider. So the
   * missing bean becomes the null which the builder expects.
   */
  private static <T> T resolved(
      final Instance<T> instance) {

    return (instance != null) && instance.isResolvable()
        ? instance.get()
        : null;

  }

}
