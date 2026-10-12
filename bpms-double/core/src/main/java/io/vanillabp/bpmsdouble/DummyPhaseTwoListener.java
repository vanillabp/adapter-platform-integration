package io.vanillabp.bpmsdouble;

/**
 * Optional hook of the dummy adapter. Integration tests use it to watch phase two of a
 * two-phase workflow start, and to make it fail. Provide a bean of this type to get
 * notified. An exception thrown here makes the dispatch fail. So a test can check how
 * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementations retry.
 */
@FunctionalInterface
public interface DummyPhaseTwoListener {

  /**
   * Called by the double's {@link DummyProcessService} whenever phase two
   * of starting a workflow is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   */
  void startedWorkflowPhaseTwo(
      Object workflowAggregateId);

  /**
   * The variant with the adapter id, called by the double. By default it calls the
   * variant without the id. Tests with several adapters (the migration scenario)
   * override this one to check WHICH double ran phase two.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   */
  default void startedWorkflowPhaseTwo(
      final String adapterId,
      final Object workflowAggregateId) {

    startedWorkflowPhaseTwo(workflowAggregateId);

  }

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
   * The variant with the adapter id. By default it calls the variant without the id. See
   * {@link #startedWorkflowPhaseTwo(String, Object)}.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The task's ID
   */
  default void completedTaskPhaseTwo(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    completedTaskPhaseTwo(workflowAggregateId, taskId);

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

  /**
   * Called whenever phase two of completing a USER task is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The user task's ID
   */
  default void completedUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {
  }

  /**
   * Called whenever phase two of canceling a USER task is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The user task's ID
   * @param bpmnErrorCode The BPMN error code
   */
  default void canceledUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {
  }

  /**
   * Called whenever phase two of correlating a message is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param messageName The BPMN message name
   * @param correlationId The correlation id or <code>null</code>
   */
  default void correlatedMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {
  }

  /**
   * Called whenever phase two of starting a workflow by message is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param messageName The BPMN message name
   */
  default void startedWorkflowByMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName) {
  }


  /**
   * Called whenever a signal is broadcast. This happens in phase one for an adapter
   * without a two-phase commit, and in phase two for an adapter with one.
   *
   * @param signalName The PLAIN BPMN signal name
   * @param phaseTwo Whether this was phase two (after the commit)
   */
  default void broadcastSignal(
      final String signalName,
      final boolean phaseTwo) {
  }

  /**
   * Called whenever a changed workflow aggregate is pushed to the BPMS. This happens in
   * phase one for an adapter without a two-phase commit, and in phase two for an adapter
   * with one.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The task whose scope received the values, or <code>null</code>
   *        for the workflow's global scope
   * @param phaseTwo Whether this was phase two (after the commit)
   */
  default void aggregateChanged(
      final Object workflowAggregateId,
      final String taskId,
      final boolean phaseTwo) {
  }

}
