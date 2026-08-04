package io.vanillabp.adapter.dummy.runtime;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

/**
 * Test hook steering the dummy adapter's
 * {@link MigratableProcessService#awarenessOfTask(Object, String)} answer -
 * integration tests probe the core's adapter election (the
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

}
