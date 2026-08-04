package io.vanillabp.adapter.dummy.springboot.processservice;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

/**
 * Test hook steering the dummy adapter's
 * {@link MigratableProcessService#awarenessOfTask(Object, String)} answer -
 * infrastructure tests probe the core's adapter election (the
 * {@code WorkflowLocator} walk) without a real BPMS. Without such a bean the dummy
 * does not know any task.
 */
public interface DummyTaskAwarenessSource {

  /**
   * The awareness the dummy adapter of the given adapter ID reports for the task.
   *
   * @param adapterId The dummy adapter's ID (several instances may be configured)
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The task's ID
   * @return The awareness or <code>null</code> to let another source answer
   *         (defaulting to {@link WorkflowAwareness#UNKNOWN_TO_BPMS})
   */
  WorkflowAwareness awarenessOfTask(
      String adapterId,
      Object workflowAggregateId,
      String taskId);

  /**
   * The awareness the dummy adapter reports for a USER task - defaults to the
   * service-task answer.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The user task's ID
   * @return The awareness or <code>null</code> to let another source answer
   */
  default WorkflowAwareness awarenessOfUserTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return awarenessOfTask(adapterId, workflowAggregateId, taskId);

  }

}
