package io.vanillabp.integration.test.workflowstart;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * A workflow service which has one method for the timer start and one method for every
 * start event of the same process. Both match the timer. Nothing says which one should
 * build the aggregate, so the application must not boot.
 */
@WorkflowService(
    workflowAggregateClass = WorkflowStartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerProcess"))
public class NamedAndGeneralStartWorkflowService {

  @WorkflowStartedByBpms(id = "DailyTimer")
  public WorkflowStartAggregate aggregateOfTimerStart(
      final BpmsStartTrigger trigger) {

    return new WorkflowStartAggregate();

  }

  @WorkflowStartedByBpms
  public WorkflowStartAggregate aggregateOfEveryStart(
      final BpmsStartTrigger trigger) {

    return new WorkflowStartAggregate();

  }

}
