package io.vanillabp.integration.test.secondary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the test which shows that a called process is no workflow of its
 * own. 'Ordering' calls 'Shipping', and 'Dispatching' is the id a renamed process left behind,
 * with no model in this application.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = OrderAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Ordering"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "Shipping"), @BpmnProcess(bpmnProcessId = "Dispatching")
    })
public class CalledProcessWorkflowService {

  /**
   * Every end the application was told about, as "aggregate|kind|end event".
   */
  public static final List<String> ENDS_REPORTED = new CopyOnWriteArrayList<>();

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

  @WorkflowEnded
  public void orderEnded(
      final OrderAggregate aggregate,
      final WorkflowEnd end) {

    ENDS_REPORTED.add("%s|%s|%s".formatted(aggregate.getId(), end.kind(), end.endEventId()));

  }

}
