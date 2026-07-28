package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.PhaseTwoCall;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;

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
 * time, together with a converter turning the serialized (String) workflow-aggregate
 * ID back into the aggregate's ID type - the platform knows the persistence layer,
 * so the conversion happens exactly once, here.
 */
public final class PhaseTwoRouter {

  private record RegistrationKey(
                                 String workflowModuleId,
                                 String bpmnProcessId) {
  }

  private record Registration(
                              MigrationProcessService<?> processService,
                              Function<String, Object> aggregateIdConverter) {
  }

  private final Map<RegistrationKey, Registration> registrations = new ConcurrentHashMap<>();

  /**
   * Register the process service of a workflow module/BPMN process as dispatch
   * target, called by the platform integration at bean-creation time.
   *
   * @param processService The process service to route calls to
   * @param aggregateIdConverter Converts the serialized (String) workflow-aggregate
   *        ID back into the aggregate's ID type
   */
  public void register(
      final MigrationProcessService<?> processService,
      final Function<String, Object> aggregateIdConverter) {

    registrations.put(
        new RegistrationKey(
            processService.getWorkflowModuleId(), processService.getBpmnProcessId()),
        new Registration(processService, aggregateIdConverter));

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

    final var registration = registrations
        .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
    if (registration == null) {
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

    final var workflowAggregateId = registration
        .aggregateIdConverter()
        .apply(call.workflowAggregateId());

    switch (call.operation()) {
      case START_WORKFLOW -> registration
          .processService()
          .startWorkflowPhaseTwo(workflowAggregateId, call.adapterId());
    }

  }

}
