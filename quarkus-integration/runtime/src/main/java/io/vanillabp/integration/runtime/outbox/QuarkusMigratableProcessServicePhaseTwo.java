package io.vanillabp.integration.runtime.outbox;

import io.vanillabp.integration.adapter.spi.MigratableProcessServicePhaseTwo;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The Quarkus implementation of {@link MigratableProcessServicePhaseTwo}: calls
 * dispatched by a {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox}
 * implementation are routed to the generated {@link ProcessServiceBaseCdiBean}
 * responsible for the workflow module and BPMN process given (the aggregate ID was
 * already converted back to its original type by the {@link PhaseTwoOutboxDispatcher}).
 */
@ApplicationScoped
public class QuarkusMigratableProcessServicePhaseTwo implements MigratableProcessServicePhaseTwo {

  @Inject
  @Any
  Instance<ProcessService<?>> processServices;

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final Object workflowAggregateId) {

    final var processService = processServices
        .stream()
        .filter(ProcessServiceBaseCdiBean.class::isInstance)
        .map(service -> (ProcessServiceBaseCdiBean<?>) service)
        .filter(service -> service.getWorkflowModuleId().equals(workflowModuleId))
        .filter(service -> service.getBpmnProcessId().equals(bpmnProcessId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            ("No ProcessService found for BPMN process '%s' of workflow module '%s'! "
                + "Maybe it was available in a previous version of your software?")
                .formatted(bpmnProcessId, workflowModuleId)));

    processService.startWorkflowPhaseTwo(adapterId, workflowAggregateId);

  }

}
