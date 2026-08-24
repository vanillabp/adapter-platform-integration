package io.vanillabp.integration.test.transaction;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the application-owned-transaction test.
 */
@WorkflowService(
    workflowAggregateClass = AppTxAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AppTxProcess"))
public class AppTxWorkflowService {

  @WorkflowTask
  public void handleTask(
      final AppTxAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("processed");

  }

  @WorkflowTask
  public void failingTask(
      final AppTxAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("must-never-be-visible");
    throw new IllegalStateException("the handler broke");

  }

}
