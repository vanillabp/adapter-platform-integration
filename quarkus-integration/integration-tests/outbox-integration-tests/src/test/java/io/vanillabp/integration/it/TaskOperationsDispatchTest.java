package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.SteerableTaskAwarenessSource;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.TaskNotFoundException;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * {@code completeTask}/{@code cancelTask} through the Quarkus JDBC phase-two
 * outbox (story 22): the adapter is elected by probing, the task ID and the BPMN
 * error code travel in the outbox entry's ARGS column, phase two dispatches after
 * the commit, a rollback leaves nothing and an unknown task raises the guiding
 * {@link TaskNotFoundException}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TaskOperationsDispatchTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(SteerableTaskAwarenessSource.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  SteerableTaskAwarenessSource awareness;

  @Inject
  UserTransaction userTransaction;

  @BeforeEach
  public void reset() {

    listener.reset();
    awareness.answerWith(WorkflowAwareness.UNKNOWN_TO_BPMS);

  }

  private Aggregate startedAggregate(
      final String content) throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = workflowService.startWorkflow(content);
      userTransaction.commit();
      return aggregate;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  @Test
  @DisplayName("completeTask and cancelTask dispatch phase two after the commit (args transported)")
  public void taskOperationsDispatchAfterCommit() throws Exception {

    final var aggregate = startedAggregate("task-ops");
    listener.awaitInvocations(1, 10000);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    userTransaction.begin();
    try {
      workflowService.completeTask(aggregate, "task-91");
      workflowService.cancelTask(aggregate, "task-92", "PAYMENT_FAILED");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + 15000;
    while (listener.getCompletedTasks().isEmpty() || listener.getCanceledTasks().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "task operations were not dispatched in time; completed: "
              + listener.getCompletedTasks()
              + ", canceled: "
              + listener.getCanceledTasks());
      Thread.sleep(50);
    }
    assertEquals(
        aggregate.getId()
            + ":task-91",
        listener.getCompletedTasks().getFirst());
    assertEquals(
        aggregate.getId()
            + ":task-92:PAYMENT_FAILED",
        listener.getCanceledTasks().getFirst());

  }

  @Test
  @DisplayName("completeUserTask and cancelUserTask dispatch phase two after the commit")
  public void userTaskOperationsDispatchAfterCommit() throws Exception {

    final var aggregate = startedAggregate("user-task-ops");
    listener.awaitInvocations(1, 10000);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    userTransaction.begin();
    try {
      workflowService.completeUserTask(aggregate, "utask-91");
      workflowService.cancelUserTask(aggregate, "utask-92", "APPROVAL_WITHDRAWN");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + 15000;
    while (listener.getCompletedUserTasks().isEmpty() || listener.getCanceledUserTasks().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "user-task operations were not dispatched in time");
      Thread.sleep(50);
    }
    assertEquals(
        aggregate.getId()
            + ":utask-91",
        listener.getCompletedUserTasks().getFirst());
    assertEquals(
        aggregate.getId()
            + ":utask-92:APPROVAL_WITHDRAWN",
        listener.getCanceledUserTasks().getFirst());

  }

  @Test
  @DisplayName("A rollback leaves no completion - and an unknown task raises TaskNotFoundException")
  public void rollbackAndUnknownTask() throws Exception {

    final var aggregate = startedAggregate("task-rollback");
    listener.awaitInvocations(1, 10000);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    userTransaction.begin();
    workflowService.completeTask(aggregate, "task-93");
    userTransaction.rollback();

    Thread.sleep(1500);
    assertTrue(listener.getCompletedTasks().isEmpty(), "phase two must never run after a rollback");

    awareness.answerWith(WorkflowAwareness.UNKNOWN_TO_BPMS);
    userTransaction.begin();
    try {
      final var exception = assertThrows(
          TaskNotFoundException.class,
          () -> workflowService.completeTask(aggregate, "task-unknown"));
      assertTrue(exception.getMessage().contains("task-unknown"));
    } finally {
      userTransaction.rollback();
    }

  }

}
