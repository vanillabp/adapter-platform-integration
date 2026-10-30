package io.vanillabp.integration.test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Plays an embedded engine with a model of two wait states: a user task, and a message
 * after it. Only a workflow whose user task was completed waits for the message. Phase one
 * of a correlation asks exactly that, from the state the engine has right now. So a
 * correlation planned while the completion is still in the outbox is refused, unless the
 * core knows better.
 * <p>
 * The completion can be held inside its dispatch. Then a test knows for sure that the
 * completion has not reached the engine when it correlates. Without that, the result
 * would depend on how fast the poller is.
 * <p>
 * It answers only for the workflows a test hands to {@link #play(Object)}, and leaves every
 * answer about tasks and workflows to the other sources (<code>null</code>). So the bean
 * changes nothing for the tests which do not use it.
 */
@ApplicationScoped
public class MessageAfterUserTaskEngine implements DummyTaskAwarenessSource, DummyPhaseTwoListener {

  /** The workflows this engine plays. It answers nothing about any other. */
  private final Set<String> played = ConcurrentHashMap.newKeySet();

  private final Set<String> waitingForTheMessage = ConcurrentHashMap.newKeySet();

  private final List<String> events = new CopyOnWriteArrayList<>();

  private volatile CountDownLatch completionHeld = new CountDownLatch(0);

  /**
   * From now on the engine answers for this workflow, which stands at its user task.
   *
   * @param workflowAggregateId The aggregate of the workflow
   */
  public void play(
      final Object workflowAggregateId) {

    played.add(String.valueOf(workflowAggregateId));

  }

  /**
   * Holds the next completions inside their dispatch until {@link #releaseTheCompletion()}.
   */
  public void holdTheCompletion() {

    completionHeld = new CountDownLatch(1);

  }

  public void releaseTheCompletion() {

    completionHeld.countDown();

  }

  /**
   * What reached the engine, in the order it arrived.
   *
   * @return Entries like <code>completed:42:utask-1</code> and <code>correlated:42:Paid</code>
   */
  public List<String> events() {

    return List.copyOf(events);

  }

  public void reset() {

    releaseTheCompletion();
    played.clear();
    waitingForTheMessage.clear();
    events.clear();

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return null;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId) {

    return null;

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return null;

  }

  @Override
  public Boolean waitsForMessage(
      final String adapterId,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    final var workflow = String.valueOf(workflowAggregateId);
    if (!played.contains(workflow)) {
      return null;
    }
    return waitingForTheMessage.contains(workflow);

  }

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    // a started workflow stands at its user task, so there is nothing to note

  }

  @Override
  public void completedUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    final var workflow = String.valueOf(workflowAggregateId);
    if (!played.contains(workflow)) {
      return;
    }
    try {
      if (!completionHeld.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("the test never released the completion");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    waitingForTheMessage.add(workflow);
    events.add("completed:%s:%s".formatted(workflowAggregateId, taskId));

  }

  @Override
  public void correlatedMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    if (!played.contains(String.valueOf(workflowAggregateId))) {
      return;
    }
    events.add("correlated:%s:%s".formatted(workflowAggregateId, messageName));

  }

}
