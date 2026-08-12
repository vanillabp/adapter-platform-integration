package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the acceptance test of BPMS-initiated starts. It serves
 * ONE of the two start events of its process, which proves both paths at once: the
 * timer start is built by VanillaBP alone, the signal start passes through the
 * application's method.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = StartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "StartProcess"))
public class StartWorkflowService {

  @WorkflowStartedByBpms(id = "SignalStart")
  public void aggregateOfSignalStart(
      final StartAggregate aggregate,
      final BpmsStartTrigger trigger,
      @TaskParam("region") final String region) {

    aggregate.setStartedBy("%s/%s".formatted(trigger.kind(), trigger.signalName()));
    aggregate.setRegion(region == null
        ? null
        : region.toUpperCase());

  }

}
