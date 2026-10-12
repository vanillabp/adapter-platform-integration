package io.vanillabp.integration.test.extension;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An extension in miniature. It shows what a case is waiting for. It reads that out of the
 * delivery log and keeps no memory of its own. It injects the RESOLVER and asks it for
 * each aggregate class, and that is the whole contract. An application may run two
 * persistences. So an extension cannot tell from outside which store holds the records of
 * an aggregate.
 */
@ApplicationScoped
public class DeliveryLogUsingExtension {

  @Inject
  TaskDeliveryLogResolver taskDeliveryLogResolver;

  /**
   * @param workflowAggregateClass The aggregate whose records are wanted
   * @return The log holding them, <code>null</code> where the application configured none
   */
  public TaskDeliveryLog logOf(
      final Class<?> workflowAggregateClass) {

    return taskDeliveryLogResolver.resolveFor(workflowAggregateClass);

  }

  /**
   * What the extension would put on a screen: the tasks this workflow still owes an answer
   * for.
   * <p>
   * An application which configured no store at all answers nothing here, because then the
   * resolver answers <code>null</code>. An extension which needs the records says so while
   * it boots. This one has a screen to fill and can live with an empty one.
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
