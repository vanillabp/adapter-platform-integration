package io.vanillabp.integration.test.persistence;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = RepositoryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class RepositoryWorkflowService {

  @Inject
  ProcessService<RepositoryAggregate> processService;

  public RepositoryAggregate startWorkflow(
      final String id) {

    final var aggregate = new RepositoryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("started");
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final RepositoryAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
