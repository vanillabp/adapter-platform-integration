package io.vanillabp.integration.test.persistence;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ActiveRecordAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class ActiveRecordWorkflowService {

  @Inject
  ProcessService<ActiveRecordAggregate> processService;

  public ActiveRecordAggregate startWorkflow(
      final String id) {

    final var aggregate = new ActiveRecordAggregate();
    aggregate.id = id;
    aggregate.status = "started";
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final ActiveRecordAggregate aggregate) {

    aggregate.status = "processed";

  }

}
