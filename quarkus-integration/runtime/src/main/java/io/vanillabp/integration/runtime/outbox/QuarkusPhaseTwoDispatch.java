package io.vanillabp.integration.runtime.outbox;

import io.vanillabp.integration.adapter.spi.PhaseTwoDispatch;
import io.vanillabp.integration.adapter.spi.ProcessServicePhaseTwo;
import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

/**
 * The Quarkus implementation of {@link PhaseTwoDispatch}: calls dispatched by a
 * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} implementation are
 * routed to the {@link ProcessServicePhaseTwo} (i.e. the generated process-service
 * bean) responsible for the workflow module and BPMN process given - there the
 * adapter to be used is determined. The aggregate ID was already converted back to
 * its original type by the {@link JdbcPhaseTwoOutboxDispatcher}.
 */
@ApplicationScoped
public class QuarkusPhaseTwoDispatch implements PhaseTwoDispatch {

  @Inject
  @Any
  Instance<ProcessService<?>> processServices;

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {
    
    final var processService = findProcessService(
        workflowModuleId,
        bpmnProcessId);
    
    processService.startWorkflowPhaseTwo(workflowAggregateId);

  }
  
  private @NonNull ProcessServicePhaseTwo findProcessService(
      final String workflowModuleId,
      final String bpmnProcessId) {
    
    return processServices
        .stream()
        .filter(ProcessServicePhaseTwo.class::isInstance)
        .map(ProcessServicePhaseTwo.class::cast)
        .filter(service -> service.getWorkflowModuleId().equals(workflowModuleId))
        .filter(service -> service.getBpmnProcessId().equals(bpmnProcessId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            ("No ProcessService found for BPMN process '%s' of workflow module '%s'! "
                + "Maybe it was available in a previous version of your software?")
                .formatted(
                    bpmnProcessId,
                    workflowModuleId)));
    
  }
  
}
