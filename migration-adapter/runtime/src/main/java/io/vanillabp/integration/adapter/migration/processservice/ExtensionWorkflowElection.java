package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;

/**
 * The election, offered to extensions. It answers from the same process services every
 * operation of VanillaBP uses, which is what makes an extension follow a workflow
 * through a migration instead of always addressing the first-priority BPMS.
 * <p>
 * The process services are looked up in the {@link PhaseTwoRouter}, where both platforms
 * register them while their beans are created - so the election works for every BPMN
 * process the application serves, without a registry of its own.
 */
public final class ExtensionWorkflowElection implements WorkflowElection {

  private final PhaseTwoRouter router;

  /**
   * Built by the platform integration as one bean per application, and handed to every
   * extension which asks for the election. The router is kept rather than read out, because
   * process services register themselves in it while the beans are still being built.
   *
   * @param router The router holding the process services of this application
   */
  public ExtensionWorkflowElection(
      final PhaseTwoRouter router) {

    this.router = router;

  }

  @Override
  public String adapterIdOfWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return locationOfWorkflow(workflowModuleId, bpmnProcessId, workflowAggregateId).adapterId();

  }

  @Override
  public io.vanillabp.integration.extension.spi.election.WorkflowLocation locationOfWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return processServiceOf(workflowModuleId, bpmnProcessId, "which BPMS holds a workflow of it cannot be elected")
        .locationOfWorkflow(workflowAggregateId);

  }

  /**
   * The process service of a BPMN process this application serves.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param whatCannotBeDone What the caller cannot do for a process nobody serves, for the message
   * @return The process service, never <code>null</code>
   * @throws IllegalStateException If no workflow service declares the process
   */
  private MigrationProcessService<?> processServiceOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String whatCannotBeDone) {

    final var processService = router.processServiceOf(workflowModuleId, bpmnProcessId);
    if (processService == null) {
      throw new IllegalStateException(
          """
              No @WorkflowService of this application declares BPMN process '%s' of workflow module \
              '%s', so %s! The workflows this application serves are: %s."""
              .formatted(
                  bpmnProcessId,
                  workflowModuleId,
                  whatCannotBeDone,
                  String.join(", ", router.registeredWorkflows())));
    }
    return processService;

  }

  @Override
  public java.util.Optional<String> workflowIdOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    // a process nobody serves is refused like it is by the election. An empty answer would read
    // as "VanillaBP does not know this workflow", and a wrong process id is a mistake of the
    // caller which an empty answer hides
    return java.util.Optional.ofNullable(
        processServiceOf(workflowModuleId, bpmnProcessId, "its workflows have no id VanillaBP wrote down")
            .workflowIdOf(workflowAggregateId));

  }

  @Override
  public java.util.Optional<io.vanillabp.integration.extension.spi.election.WorkflowStart> workflowStartOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    // the same reading as the id alone, refused in the same case
    return java.util.Optional.ofNullable(
        processServiceOf(workflowModuleId, bpmnProcessId, "its workflows have no id VanillaBP wrote down")
            .workflowStartOf(workflowAggregateId));

  }

  @Override
  public java.util.List<io.vanillabp.integration.extension.spi.election.OpenUserTask> openUserTasksOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    final var processService = router.processServiceOf(workflowModuleId, bpmnProcessId);
    if (processService == null) {
      // the same as for the id alone: about a BPMN process this application does not serve, a
      // read knows nothing
      return java.util.List.of();
    }
    return processService.openUserTasksOf(workflowAggregateId);

  }

  @Override
  public boolean isInsideTheStartOf(
      final Object workflowAggregate) {

    return RunningBpmsInitiatedStart
        .current()
        .map(running -> router.processServiceOf(running.workflowModuleId(), running.bpmnProcessId()))
        .map(processService -> processService.isInsideTheStartOf(workflowAggregate))
        .orElse(false);

  }

}
