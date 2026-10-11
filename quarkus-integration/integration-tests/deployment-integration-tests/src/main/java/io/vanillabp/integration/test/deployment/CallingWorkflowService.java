package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serves the process 'Calling' of a BPMN file. The file has a second executable process,
 * 'Called', which nobody serves. {@code UnclaimedBpmnProcessTest} uses it. A WARN reports
 * the unclaimed process next to this one, and this one is wired as before.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Calling"))
public class CallingWorkflowService {

  @WorkflowTask
  public void juhu(
      final TaskAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
