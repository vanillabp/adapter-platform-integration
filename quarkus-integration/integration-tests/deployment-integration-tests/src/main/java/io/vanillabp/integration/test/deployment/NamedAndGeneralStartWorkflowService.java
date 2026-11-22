package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A workflow service which has one method for the timer start and one method for every
 * start event of the same process. Both match the timer. Nothing says which one should
 * build the aggregate, so the application must not boot.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = StartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "StartProcess"))
public class NamedAndGeneralStartWorkflowService {

  @WorkflowStartedByBpms(id = "DailyTimer")
  public StartAggregate aggregateOfTimerStart(
      final BpmsStartTrigger trigger) {

    return new StartAggregate();

  }

  @WorkflowStartedByBpms
  public StartAggregate aggregateOfEveryStart(
      final BpmsStartTrigger trigger) {

    return new StartAggregate();

  }

}
