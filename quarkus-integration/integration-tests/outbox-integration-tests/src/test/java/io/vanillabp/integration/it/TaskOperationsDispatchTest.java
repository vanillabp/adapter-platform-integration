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

import io.quarkus.test.QuarkusExtensionTest;
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
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
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
  @DisplayName("correlateMessage dispatches phase two after the commit; a correlation id deduplicates")
  public void correlateMessageDispatchesAfterCommit() throws Exception {

    final var aggregate = startedAggregate("correlate");
    listener.awaitInvocations(1, 10000);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    userTransaction.begin();
    try {
      workflowService.correlateMessage(aggregate, "PaymentReceived");
      // no correlation id: NO idempotency key - both dispatch
      workflowService.correlateMessage(aggregate, "PaymentReceived");
      // with correlation id: the duplicate schedule is a no-op
      workflowService.correlateMessage(aggregate, "ItemShipped", "item-4711");
      workflowService.correlateMessage(aggregate, "ItemShipped", "item-4711");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + 15000;
    while (listener.getCorrelatedMessages().size() < 3) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "correlations were not dispatched in time: "
              + listener.getCorrelatedMessages());
      Thread.sleep(50);
    }
    Thread.sleep(1500);
    assertEquals(
        2,
        listener
            .getCorrelatedMessages()
            .stream()
            .filter(entry -> entry.contains(":PaymentReceived:"))
            .count(),
        "without a correlation id both correlations dispatch");
    assertEquals(
        1,
        listener
            .getCorrelatedMessages()
            .stream()
            .filter(entry -> entry.contains(":ItemShipped:"))
            .count(),
        "with a correlation id the duplicate schedule is a no-op");
    assertTrue(listener
        .getCorrelatedMessages()
        .contains(aggregate.getId()
            + ":ItemShipped:item-4711"));

  }

  @Test
  @DisplayName("Correlating with an unknown workflow raises the guiding WorkflowNotFoundException")
  public void unknownWorkflowRaisesGuidingException() throws Exception {

    final var aggregate = startedAggregate("correlate-unknown");
    listener.awaitInvocations(1, 10000);
    // awareness stays UNKNOWN_TO_BPMS

    userTransaction.begin();
    try {
      final var exception = assertThrows(
          io.vanillabp.spi.process.WorkflowNotFoundException.class,
          () -> workflowService.correlateMessage(aggregate, "PaymentReceived"));
      assertTrue(exception.getMessage().contains("PaymentReceived"));
      assertTrue(
          exception.getMessage().contains("startWorkflowByMessage"),
          "expected the start-by-message hint but got: "
              + exception.getMessage());
    } finally {
      userTransaction.rollback();
    }

  }

  @Test
  @DisplayName("A COMPLETED workflow makes correlateMessage a warned no-op")
  public void completedWorkflowCorrelationIsNoOp() throws Exception {

    final var aggregate = startedAggregate("correlate-completed");
    listener.awaitInvocations(1, 10000);
    awareness.answerWith(WorkflowAwareness.COMPLETED);

    userTransaction.begin();
    try {
      workflowService.correlateMessage(aggregate, "PaymentReceived");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    Thread.sleep(1500);
    assertTrue(listener.getCorrelatedMessages().isEmpty());

  }

  @Test
  @DisplayName("startWorkflowByMessage uses start semantics and dispatches phase two after the commit")
  public void startWorkflowByMessageDispatchesAfterCommit() throws Exception {

    userTransaction.begin();
    final io.vanillabp.integration.test.Aggregate aggregate;
    try {
      aggregate = workflowService.startWorkflowByMessage("start-by-message", "OrderPlaced");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + 15000;
    while (listener.getStartedByMessage().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "start-by-message was not dispatched in time");
      Thread.sleep(50);
    }
    assertEquals(
        aggregate.getId()
            + ":OrderPlaced",
        listener.getStartedByMessage().getFirst());

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
