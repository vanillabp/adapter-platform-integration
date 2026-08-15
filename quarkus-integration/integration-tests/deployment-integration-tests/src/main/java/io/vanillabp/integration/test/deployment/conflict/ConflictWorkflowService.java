package io.vanillabp.integration.test.deployment.conflict;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the version-conflict acceptance test (story 59). Both
 * handlers change the aggregate; only {@link #conflictingTask} lets the other branch
 * commit in between, which makes VanillaBP's own commit fail.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ConflictAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ConflictProcess"))
public class ConflictWorkflowService {

  @Inject
  ConcurrentBranch concurrentBranch;

  @WorkflowTask(taskDefinition = "conflictingTask")
  public void conflictingTask(
      final ConflictAggregate aggregate) {

    aggregate.setContent("changed by the task");
    concurrentBranch.changeInOwnTransaction(aggregate.getId());

  }

  @WorkflowTask(taskDefinition = "undisturbedTask")
  public void undisturbedTask(
      final ConflictAggregate aggregate) {

    aggregate.setContent("changed by the task");

  }

}
