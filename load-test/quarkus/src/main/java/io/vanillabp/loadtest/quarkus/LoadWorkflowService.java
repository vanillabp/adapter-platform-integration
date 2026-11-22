package io.vanillabp.loadtest.quarkus;

import io.vanillabp.loadharness.LoadAggregate;
import io.vanillabp.loadharness.LoadProcess;
import io.vanillabp.loadharness.LoadTasks;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the load process. It hands every task to {@link LoadTasks}, so
 * both platforms run the same handlers.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = LoadAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = LoadProcess.BPMN_PROCESS_ID))
public class LoadWorkflowService {

  @WorkflowTask(taskDefinition = LoadProcess.STEP)
  public void step(
      final LoadAggregate aggregate) {

    LoadTasks.step(aggregate);

  }

  @WorkflowTask(taskDefinition = LoadProcess.WAIT)
  public void waitForTheApplication(
      final LoadAggregate aggregate,
      @TaskId final String taskId) {

    LoadTasks.waitForTheApplication(aggregate, taskId);

  }

}
