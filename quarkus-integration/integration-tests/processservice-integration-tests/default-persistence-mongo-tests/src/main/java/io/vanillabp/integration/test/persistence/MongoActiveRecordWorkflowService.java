package io.vanillabp.integration.test.persistence;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = MongoActiveRecordAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class MongoActiveRecordWorkflowService {

  @Inject
  ProcessService<MongoActiveRecordAggregate> processService;

  public MongoActiveRecordAggregate startWorkflow(
      final String id) {

    final var aggregate = new MongoActiveRecordAggregate();
    aggregate.id = id;
    aggregate.status = "started";
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final MongoActiveRecordAggregate aggregate) {

    aggregate.status = "processed";

  }

}
