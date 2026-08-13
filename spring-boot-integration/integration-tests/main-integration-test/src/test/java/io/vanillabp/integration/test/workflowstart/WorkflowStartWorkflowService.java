package io.vanillabp.integration.test.workflowstart;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * The workflow service of the acceptance test of BPMS-initiated starts. It serves
 * ONE of the two start events of its process, which is what proves both paths at
 * once: the timer start is built by VanillaBP alone, the signal start passes through
 * the application's method.
 */
@WorkflowService(
    workflowAggregateClass = WorkflowStartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerProcess"))
public class WorkflowStartWorkflowService {

  /**
   * Story 43: the same workflow service also wants to know when a workflow ended.
   */
  @WorkflowEnded
  public void workflowEnded(
      final WorkflowStartAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setRegion("%s/%s".formatted(end.kind(), end.endEventId()));

  }

  @WorkflowStartedByBpms(id = "SignalStart")
  public void aggregateOfSignalStart(
      final WorkflowStartAggregate aggregate,
      final BpmsStartTrigger trigger,
      @TaskParam("region") final String region) {

    aggregate.setStartedBy("%s/%s".formatted(trigger.kind(), trigger.signalName()));
    aggregate.setRegion(region == null
        ? null
        : region.toUpperCase());

  }

}
