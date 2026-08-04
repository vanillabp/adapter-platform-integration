package io.vanillabp.integration.test.outbox;

import io.vanillabp.adapter.dummy.springboot.processservice.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

/**
 * Steers the dummy adapter's task awareness per test: the answer set here is
 * returned for every probe (default {@link WorkflowAwareness#UNKNOWN_TO_BPMS}).
 */
public class SteerableTaskAwarenessSource implements DummyTaskAwarenessSource {

  /**
   * The answer a NEW context starts with - the recovery test needs the freshly
   * restarted context to answer ACTIVE before its outbox dispatcher polls.
   */
  public static volatile WorkflowAwareness initialAnswer = WorkflowAwareness.UNKNOWN_TO_BPMS;

  /**
   * The answer for WORKFLOW-level probes (message correlation, and the START
   * re-dispatch mitigation of story 25) if it has to differ from the task-level
   * one - <code>null</code> means "same as {@link #initialAnswer}". Needed by the
   * recovery test: it wants the recovered START entry to be dispatched again
   * (workflow unknown) while the recovered COMPLETE_TASK entry elects an adapter
   * knowing the task.
   */
  public static volatile WorkflowAwareness initialWorkflowAnswer = null;

  private volatile WorkflowAwareness answer = initialAnswer;

  private volatile WorkflowAwareness workflowAnswer = initialWorkflowAnswer;

  public void answerWith(
      final WorkflowAwareness awareness) {

    this.answer = awareness;

  }

  public void answerWorkflowProbesWith(
      final WorkflowAwareness awareness) {

    this.workflowAnswer = awareness;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return answer;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId) {

    return workflowAnswer != null
        ? workflowAnswer
        : answer;

  }

}
