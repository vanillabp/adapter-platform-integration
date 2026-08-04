package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;

/**
 * Core-owned router dispatching {@link PhaseTwoCall}s scheduled via
 * {@link PhaseTwoOutbox} to the {@link MigrationProcessService} of the workflow
 * module/BPMN process:
 *
 * <pre>
 * PhaseTwoOutbox (store)
 *     --&gt; PhaseTwoRouter.dispatch(call)
 *         --&gt; MigrationProcessService (adapter selection)
 *             --&gt; MigratableProcessService (BPMS adapter)
 * </pre>
 *
 * The platform integration registers every process-service bean at bean-creation
 * time. The serialized (String) workflow-aggregate ID of an outbox entry is
 * converted back into the aggregate's ID type by the process service itself
 * ({@link MigrationProcessService#convertAggregateId} - the ID type comes from the
 * aggregate's persistence support), so the conversion happens exactly once, here.
 */
public final class PhaseTwoRouter {

  private record RegistrationKey(
                                 String workflowModuleId,
                                 String bpmnProcessId) {
  }

  private final Map<RegistrationKey, MigrationProcessService<?>> registrations = new ConcurrentHashMap<>();

  /**
   * Register the process service of a workflow module/BPMN process as dispatch
   * target, called by the platform integration at bean-creation time.
   *
   * @param processService The process service to route calls to
   */
  public void register(
      final MigrationProcessService<?> processService) {

    registrations.put(
        new RegistrationKey(
            processService.getWorkflowModuleId(), processService.getBpmnProcessId()),
        processService);

  }

  /**
   * Dispatch the given phase-two call to the process service registered for its
   * workflow module/BPMN process.
   *
   * @param call The phase-two call to dispatch
   * @throws IllegalStateException If no process service is registered for the
   *         call's workflow module/BPMN process (e.g. the BPMN process is no longer
   *         part of this application) - the outbox entry stays visible in the
   *         outbox store for operations
   */
  public void dispatch(
      final PhaseTwoCall call) {

    final var processService = registrations
        .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
    if (processService == null) {
      throw new IllegalStateException(
          """
              Cannot dispatch outbox entry (operation '%s', aggregate ID '%s'): BPMN process '%s' of \
              workflow module '%s' is not (or no longer) part of this application! If the process was \
              removed on purpose, remove the entry from the outbox store - it stays visible there for \
              operations."""
              .formatted(
                  call.operation(),
                  call.workflowAggregateId(),
                  call.bpmnProcessId(),
                  call.workflowModuleId()));
    }

    final var workflowAggregateId = processService
        .convertAggregateId(call.workflowAggregateId());

    switch (call.operation()) {
      case START_WORKFLOW -> processService
          .startWorkflowPhaseTwo(workflowAggregateId, call.adapterId());
      case COMPLETE_TASK -> processService
          .completeTaskPhaseTwo(
              workflowAggregateId,
              call.args().get(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID));
      case CANCEL_TASK -> processService
          .cancelTaskPhaseTwo(
              workflowAggregateId,
              call.args().get(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID),
              call.args().get(io.vanillabp.integration.spi.PhaseTwoCall.ARG_BPMN_ERROR_CODE));
    }

  }

}
