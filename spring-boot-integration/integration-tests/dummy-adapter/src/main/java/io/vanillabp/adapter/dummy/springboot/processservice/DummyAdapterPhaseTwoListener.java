package io.vanillabp.adapter.dummy.springboot.processservice;

/**
 * Optional hook of the dummy adapter used by integration tests to observe (and
 * possibly fail) phase two of a two-phase workflow start. Provide a bean of this type
 * to get notified; throwing an exception makes the dispatch fail, so retry behavior of
 * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementations can be
 * tested.
 */
@FunctionalInterface
public interface DummyAdapterPhaseTwoListener {

  /**
   * Called by the dummy adapter's {@link MigratableProcessService} whenever phase two
   * of starting a workflow is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   */
  void startedWorkflowPhaseTwo(
      Object workflowAggregateId);

  /**
   * Called whenever phase two of completing an asynchronous task is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The task's ID
   */
  default void completedTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {
  }

  /**
   * Called whenever phase two of canceling an asynchronous task is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The task's ID
   * @param bpmnErrorCode The BPMN error code
   */
  default void canceledTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {
  }

}
