package io.vanillabp.integration.test.mixed;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = MongoAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MongoProcess"))
public class MongoWorkflowService {

  @Inject
  ProcessService<MongoAggregate> processService;

  public MongoAggregate startWorkflow(
      final String id) {

    final var aggregate = new MongoAggregate();
    aggregate.id = id;
    aggregate.status = "started";
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final MongoAggregate aggregate) {

    aggregate.status = "processed";

  }

}
