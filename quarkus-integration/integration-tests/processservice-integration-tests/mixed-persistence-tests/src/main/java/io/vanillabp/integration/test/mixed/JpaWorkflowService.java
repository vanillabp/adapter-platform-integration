package io.vanillabp.integration.test.mixed;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = JpaAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "JpaProcess"))
public class JpaWorkflowService {

  @Inject
  ProcessService<JpaAggregate> processService;

  public JpaAggregate startWorkflow(
      final String id) {

    final var aggregate = new JpaAggregate();
    aggregate.id = id;
    aggregate.status = "started";
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final JpaAggregate aggregate) {

    aggregate.status = "processed";

  }

}
