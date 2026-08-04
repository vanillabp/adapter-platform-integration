package io.vanillabp.integration.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.adapter.dummy.runtime.DummyPhaseTwoListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records phase-two invocations of the dummy adapter and optionally fails a
 * configurable number of dispatches (to test retry behavior of the outbox).
 */
@ApplicationScoped
public class RecordingPhaseTwoListener implements DummyPhaseTwoListener {

  private final List<Object> invocations = new CopyOnWriteArrayList<>();

  private final AtomicInteger failuresRemaining = new AtomicInteger(0);

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    invocations.add(workflowAggregateId);
    if (failuresRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
      throw new RuntimeException("phase two failed for testing purposes");
    }

  }

  /**
   * Recorded phase-two task completions as "aggregateId:taskId" and cancellations
   * as "aggregateId:taskId:errorCode".
   */
  private final List<String> completedTasks = new CopyOnWriteArrayList<>();

  private final List<String> canceledTasks = new CopyOnWriteArrayList<>();

  @Override
  public void completedTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    completedTasks.add(workflowAggregateId
        + ":"
        + taskId);

  }

  @Override
  public void canceledTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    canceledTasks.add(workflowAggregateId
        + ":"
        + taskId
        + ":"
        + bpmnErrorCode);

  }

  private final List<String> completedUserTasks = new CopyOnWriteArrayList<>();

  private final List<String> canceledUserTasks = new CopyOnWriteArrayList<>();

  @Override
  public void completedUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    completedUserTasks.add(workflowAggregateId
        + ":"
        + taskId);

  }

  @Override
  public void canceledUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    canceledUserTasks.add(workflowAggregateId
        + ":"
        + taskId
        + ":"
        + bpmnErrorCode);

  }

  public List<String> getCompletedUserTasks() {

    return List.copyOf(completedUserTasks);

  }

  public List<String> getCanceledUserTasks() {

    return List.copyOf(canceledUserTasks);

  }

  public List<String> getCompletedTasks() {

    return List.copyOf(completedTasks);

  }

  public List<String> getCanceledTasks() {

    return List.copyOf(canceledTasks);

  }

  public void failNextDispatches(
      final int numberOfFailures) {

    failuresRemaining.set(numberOfFailures);

  }

  public List<Object> getInvocations() {

    return List.copyOf(invocations);

  }

  public void reset() {

    invocations.clear();
    completedTasks.clear();
    canceledTasks.clear();
    completedUserTasks.clear();
    canceledUserTasks.clear();
    failuresRemaining.set(0);

  }

  /**
   * Waits until at least the given number of phase-two invocations were recorded.
   *
   * @param numberOfInvocations The number of invocations to wait for
   * @param timeoutMillis How long to wait at most
   * @return All invocations recorded so far
   * @throws AssertionError If the invocations did not happen within the timeout
   */
  public List<Object> awaitInvocations(
      final int numberOfInvocations,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (invocations.size() < numberOfInvocations) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "Expected at least %d phase-two invocation(s) within %dms but got %d!"
                .formatted(numberOfInvocations, timeoutMillis, invocations.size()));
      }
      Thread.sleep(50);
    }
    return getInvocations();

  }

}
