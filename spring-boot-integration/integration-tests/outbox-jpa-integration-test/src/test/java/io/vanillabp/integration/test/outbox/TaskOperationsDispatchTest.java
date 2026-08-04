package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * The asynchronous-task operations {@code completeTask}/{@code cancelTask} through
 * the phase-two outbox (story 22), using the dummy adapter forced to require a
 * two-phase commit:
 * <ul>
 * <li>the adapter is elected by probing ({@code awarenessOfTask}) and phase two is
 * dispatched AFTER the commit (task ID and BPMN error code travel in the outbox
 * entry's args);</li>
 * <li>a rollback leaves no entry - phase two never runs;</li>
 * <li>an unknown task raises the guiding {@link TaskNotFoundException};</li>
 * <li>a task reported COMPLETED is an idempotent no-op (no outbox entry).</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class TaskOperationsDispatchTest {

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private SteerableTaskAwarenessSource awareness;

  @BeforeEach
  public void reset() {

    listener.reset();
    awareness.answerWith(WorkflowAwareness.UNKNOWN_TO_BPMS);

  }

  private Aggregate startedAggregate(
      final String content) {

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attached);
    return attached;

  }

  @Test
  @DisplayName("completeTask elects the adapter by probing and dispatches phase two after the commit")
  public void completeTaskDispatchesPhaseTwoAfterCommit() throws Exception {

    final var aggregate = startedAggregate("complete-task");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    transactionTemplate.executeWithoutResult(status -> processService
        .completeTask(aggregate, "task-77"));

    final var completions = listener.awaitCompletedTasks(1, 10000);
    assertEquals(
        aggregate.getId()
            + ":task-77",
        completions.getFirst());

  }

  @Test
  @DisplayName("cancelTask carries the BPMN error code through the outbox entry's args")
  public void cancelTaskDispatchesWithErrorCode() throws Exception {

    final var aggregate = startedAggregate("cancel-task");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    transactionTemplate.executeWithoutResult(status -> processService
        .cancelTask(aggregate, "task-78", "PAYMENT_FAILED"));

    final var cancellations = listener.awaitCanceledTasks(1, 10000);
    assertEquals(
        aggregate.getId()
            + ":task-78:PAYMENT_FAILED",
        cancellations.getFirst());

  }

  @Test
  @DisplayName("A rollback leaves no outbox entry - phase two of the completion never runs")
  public void rollbackLeavesNoCompletion() throws Exception {

    final var aggregate = startedAggregate("rollback-complete");
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          processService.completeTask(aggregate, "task-79");
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    Thread.sleep(1500);
    assertTrue(listener.getCompletedTasks().isEmpty(), "phase two must never run after a rollback");

  }

  @Test
  @DisplayName("No adapter knows the task - the guiding TaskNotFoundException is raised")
  public void unknownTaskRaisesGuidingException() {

    final var aggregate = startedAggregate("unknown-task");
    // awareness stays UNKNOWN_TO_BPMS

    final var exception = assertThrowsExactly(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> processService
            .completeTask(aggregate, "task-unknown")));

    assertTrue(exception.getMessage().contains("task-unknown"));
    assertTrue(
        exception.getMessage().contains("prioritized"),
        "expected the probed adapters to be mentioned but got: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("A task reported COMPLETED is an idempotent no-op - no phase two is scheduled")
  public void completedTaskIsIdempotentNoOp() throws Exception {

    final var aggregate = startedAggregate("already-completed");
    awareness.answerWith(WorkflowAwareness.COMPLETED);

    final var result = transactionTemplate.execute(status -> processService
        .completeTask(aggregate, "task-80"));

    assertNotNull(result);
    Thread.sleep(1500);
    assertTrue(listener.getCompletedTasks().isEmpty(), "an already-completed task must not dispatch");

  }

}
