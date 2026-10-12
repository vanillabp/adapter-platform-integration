package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serves the process 'Calling' and declares a second method for a task nobody modelled.
 * {@code OrphanMethodNextToUnclaimedProcessTest} uses it. The boot only warns about the
 * other process. That warning must not make the wiring check in the other direction any
 * quieter.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Calling"))
public class OrphanMethodCallingWorkflowService {

  @WorkflowTask
  public void juhu(
      final TaskAggregate aggregate) {

    aggregate.setStatus("processed");

  }

  @WorkflowTask(taskDefinition = "activityNobodyModelled")
  public void typo() {

  }

}
