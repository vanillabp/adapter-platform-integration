package io.vanillabp.integration.test.outbox;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

/**
 * Steers the task awareness of the dummy adapter per test. Every probe gets the answer
 * set here (default {@link WorkflowAwareness#UNKNOWN_TO_BPMS}).
 */
public class SteerableTaskAwarenessSource implements DummyTaskAwarenessSource {

  /**
   * The answer a NEW context starts with. The recovery test needs the restarted context
   * to answer ACTIVE before its outbox dispatcher polls.
   */
  public static volatile WorkflowAwareness initialAnswer = WorkflowAwareness.UNKNOWN_TO_BPMS;

  /**
   * The answer for probes on WORKFLOW level (message correlation, and the mitigation for a
   * START dispatched again), if it must differ from the answer on task level.
   * <code>null</code> means "same as {@link #initialAnswer}". The recovery test needs
   * this. It wants the recovered START entry dispatched again (workflow unknown). At the
   * same time the recovered COMPLETE_TASK entry must elect an adapter which knows the
   * task.
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

  /**
   * How many WORKFLOW probes still answer UNKNOWN_TO_BPMS before the configured answer
   * applies. This plays an eventually consistent BPMS: the workflow exists, but its BPMS
   * cannot find it yet.
   */
  private final java.util.concurrent.atomic.AtomicInteger invisibleProbes = new java.util.concurrent.atomic.AtomicInteger();

  /**
   * The window this adapter reports, or <code>null</code> for an immediately
   * consistent BPMS (the default of this test double).
   */
  private volatile io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay visibilityDelay;

  /**
   * Lets the next <code>probes</code> workflow probes answer "not visible yet". Meanwhile
   * this adapter reports the given window.
   *
   * @param probes How many probes report the workflow as unknown
   * @param window The window the adapter asks the core to wait
   */
  public void becomeVisibleAfter(
      final int probes,
      final java.time.Duration window) {

    invisibleProbes.set(probes);
    this.visibilityDelay = new io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay(
        window, java.time.Duration.ofMillis(20));

  }

  public void alwaysVisible() {

    invisibleProbes.set(0);
    this.visibilityDelay = null;

  }

  /**
   * How many probes the current test has left over. A test which asserts that the core
   * waited wants this at zero.
   *
   * @return The remaining invisible probes
   */
  public int remainingInvisibleProbes() {

    return invisibleProbes.get();

  }

  @Override
  public io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay workflowVisibilityDelay(
      final String adapterId) {

    return visibilityDelay;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId) {

    if (invisibleProbes.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }
    return workflowAnswer != null
        ? workflowAnswer
        : answer;

  }

}
