package io.vanillabp.integration.test.secondary;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * One workflow service with two BPMN processes: the one which the application addresses,
 * and the one which it calls. The task of the called process is asynchronous. So the
 * delivery leaves it open, and a later completion is elected from its record.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = OrderAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Ordering"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "Shipping"))
public class OrderWorkflowService {

  @WorkflowTask(taskDefinition = "orderTask")
  public void orderTask(
      final OrderAggregate aggregate) {

    aggregate.setStatus("ordered");

  }

  @WorkflowTask(taskDefinition = "awaitShipment")
  public void awaitShipment(
      final OrderAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setStatus("awaiting-shipment");

  }

}
