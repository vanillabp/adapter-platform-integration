package io.vanillabp.integration.test.extension;

import java.util.List;

import org.springframework.stereotype.Component;

import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * An extension in miniature. It shows what a case is waiting for. It reads that from the
 * delivery log and keeps no memory of its own. It injects the RESOLVER and asks it per
 * aggregate class. That is the whole contract. An application may run two persistences.
 * An extension cannot tell from outside which store holds the records of an aggregate.
 */
@Component
public class DeliveryLogUsingExtension {

  private final TaskDeliveryLogResolver taskDeliveryLogResolver;

  public DeliveryLogUsingExtension(
      final TaskDeliveryLogResolver taskDeliveryLogResolver) {

    this.taskDeliveryLogResolver = taskDeliveryLogResolver;

  }

  /**
   * @param workflowAggregateClass The aggregate whose records are wanted
   * @return The log holding them, <code>null</code> where the application configured none
   */
  public TaskDeliveryLog logOf(
      final Class<?> workflowAggregateClass) {

    return taskDeliveryLogResolver.resolveFor(workflowAggregateClass);

  }

  /**
   * What the extension would put on a screen: the tasks for which this workflow still owes
   * an answer.
   * <p>
   * If the application configured no store at all, the answer here is empty, because the
   * resolver then answers <code>null</code>. An extension which needs the records says so
   * while it boots. This one has a screen to fill and accepts an empty one.
   *
   * @param workflowAggregateClass The aggregate whose log is asked
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @return The open records, oldest first, empty where the application has no store
   */
  public List<TaskDelivery> openTasksOf(
      final Class<?> workflowAggregateClass,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var deliveryLog = logOf(workflowAggregateClass);
    if (deliveryLog == null) {
      return List.of();
    }
    return deliveryLog.openTasksOfAggregate(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

}
