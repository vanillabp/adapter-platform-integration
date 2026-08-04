package io.vanillabp.integration.test;

import io.vanillabp.adapter.dummy.runtime.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Steers the dummy adapter's task awareness per test: the answer set here is
 * returned for every probe (default {@link WorkflowAwareness#UNKNOWN_TO_BPMS}).
 */
@ApplicationScoped
public class SteerableTaskAwarenessSource implements DummyTaskAwarenessSource {

  private volatile WorkflowAwareness answer = WorkflowAwareness.UNKNOWN_TO_BPMS;

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
