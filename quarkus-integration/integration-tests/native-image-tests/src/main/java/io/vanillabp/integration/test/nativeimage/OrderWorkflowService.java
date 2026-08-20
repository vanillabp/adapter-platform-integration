package io.vanillabp.integration.test.nativeimage;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A workflow service of the smallest useful shape: it starts a workflow and handles one
 * task. Its purpose here is to make the extension generate everything it generates for a
 * real application - the process-service bean, the transaction-runner resolution and the
 * coverage verdict of story 70 among them - so the native image has to link all of it.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = OrderAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class OrderWorkflowService {

  @Inject
  ProcessService<OrderAggregate> processService;

  public OrderAggregate startWorkflow(
      final String id) {

    final var aggregate = new OrderAggregate();
    aggregate.setId(id);
    aggregate.setStatus("started");
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final OrderAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
