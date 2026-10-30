package io.vanillabp.bpmsdouble;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

/**
 * Test hook which steers the answer of the dummy adapter's
 * {@link DummyProcessService#awarenessOfTask}. With it, integration tests probe the
 * core's adapter election (the {@code WorkflowLocator} walk) without a real BPMS.
 * Without such a bean the double knows no task.
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
   * The awareness the dummy adapter reports for a WORKFLOW, used by the probes for
   * message correlation. By default it is the answer for a service task.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The awareness or <code>null</code> to let another source answer
   */
  default WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId) {

    return awarenessOfTask(adapterId, workflowAggregateId, null);

  }

  /**
   * The awareness the dummy adapter reports for a WORKFLOW whose BPMS id VanillaBP holds.
   * An adapter of an eventually consistent BPMS uses this shortcut: it asks its engine by
   * key instead of searching its read model. By default it is the answer without the id.
   * An adapter which cannot use the id answers the same way.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> where
   *          VanillaBP holds none
   * @return The awareness or <code>null</code> to let another source answer
   */
  default WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId,
      final String workflowId) {

    return awarenessOfWorkflow(adapterId, workflowAggregateId);

  }

  /**
   * Whether the dummy adapter of the given ID can ask its BPMS whether it holds a
   * workflow. Without this ability, the core refuses to combine the adapter with a
   * second one. A Camunda 8 cluster without secondary storage answers <code>false</code>
   * here.
   *
   * @param adapterId The dummy adapter's ID
   * @return Whether the workflow probe of that adapter asks rather than guesses
   */
  default boolean canLocateWorkflows(
      final String adapterId) {

    return true;

  }

  /**
   * The awareness the dummy adapter reports for a USER task. By default it is the answer
   * for a service task.
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

  /**
   * Whether the workflow waits for a message right now. The double asks this in phase one of
   * a correlation and refuses the correlation where the answer is <code>false</code>, as an
   * embedded engine does. By default nobody answers, and the double accepts every
   * correlation.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param messageName The message name as the application passed it
   * @param correlationId The correlation id, or <code>null</code>
   * @return Whether the workflow waits for it, or <code>null</code> to let another source
   *         answer
   */
  default Boolean waitsForMessage(
      final String adapterId,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    return null;

  }

  /**
   * The visibility window the dummy adapter reports. It says how long the core keeps
   * asking a hinted adapter which answers {@link WorkflowAwareness#UNKNOWN_TO_BPMS}.
   * <code>null</code> means none. An adapter of an immediately consistent BPMS reports
   * that.
   *
   * @param adapterId The dummy adapter's ID
   * @return The delay or <code>null</code>
   */
  default io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay workflowVisibilityDelay(
      final String adapterId) {

    return null;

  }

}
