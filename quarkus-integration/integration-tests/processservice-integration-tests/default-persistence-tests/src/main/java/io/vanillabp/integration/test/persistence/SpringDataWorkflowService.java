package io.vanillabp.integration.test.persistence;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = SpringDataAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class SpringDataWorkflowService {

  @Inject
  ProcessService<SpringDataAggregate> processService;

  public SpringDataAggregate startWorkflow(
      final String id) {

    final var aggregate = new SpringDataAggregate();
    aggregate.setId(id);
    aggregate.setStatus("started");
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final SpringDataAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
