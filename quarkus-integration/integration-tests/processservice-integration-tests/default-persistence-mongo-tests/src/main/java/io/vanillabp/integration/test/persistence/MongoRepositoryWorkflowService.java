package io.vanillabp.integration.test.persistence;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = MongoRepositoryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class MongoRepositoryWorkflowService {

  @Inject
  ProcessService<MongoRepositoryAggregate> processService;

  public MongoRepositoryAggregate startWorkflow(
      final String id) {

    final var aggregate = new MongoRepositoryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("started");
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final MongoRepositoryAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
