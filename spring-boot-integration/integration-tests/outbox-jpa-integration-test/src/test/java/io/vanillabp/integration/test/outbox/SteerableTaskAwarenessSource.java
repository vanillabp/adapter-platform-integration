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

  private volatile WorkflowAwareness answer = initialAnswer;

  public void answerWith(
      final WorkflowAwareness awareness) {

    this.answer = awareness;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return answer;

  }

}
