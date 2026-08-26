package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The second round of a loop is a new operation, not a redelivery. An idempotency key
 * deduplicates what is still waiting for its dispatch, so correlating the same message
 * with the same correlation id again - after the first one reached the BPMS - reaches it
 * as well, while a duplicate scheduled while the first one is still pending stays a
 * no-op (decision 22 in the repository's DECISIONS.md).
 * <p>
 * This is the Quarkus JDBC store, where the window is the column <code>DEDUP_KEY</code>
 * the dispatcher frees when it marks an entry DONE.
 */
@ExtendWith(SuppressOutputExtension.class)
public class RepeatedOperationTest {

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
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:repeated-operation-it;DB_CLOSE_DELAY=-1");

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

  private <T> T inTransaction(
      final java.util.concurrent.Callable<T> work) throws Exception {

    userTransaction.begin();
    try {
      final var result = work.call();
      userTransaction.commit();
      return result;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  private void awaitCorrelations(
      final int count) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (listener.getCorrelatedMessages().size() < count) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected %d correlation(s) but got %s".formatted(count, listener.getCorrelatedMessages()));
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("The same correlation in a second round reaches the BPMS again")
  public void aSecondRoundCorrelatesAgain() throws Exception {

    final var aggregate = inTransaction(() -> workflowService.startWorkflow("second-round"));
    listener.awaitInvocations(1, 30_000);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    // first round: partner 42 is asked
    inTransaction(() -> workflowService.correlateMessage(aggregate, "OfferRequested", "partner-42"));
    awaitCorrelations(1);

    // second round: the same partner is asked again, with the same correlation id,
    // which used to be swallowed for the whole retention period
    inTransaction(() -> workflowService.correlateMessage(aggregate, "OfferRequested", "partner-42"));
    awaitCorrelations(2);

    assertEquals(
        2,
        listener
            .getCorrelatedMessages()
            .stream()
            .filter(entry -> entry.equals(aggregate.getId()
                + ":OfferRequested:partner-42"))
            .count(),
        "both rounds reached the BPMS: "
            + listener.getCorrelatedMessages());

  }

  @Test
  @DisplayName("A duplicate scheduled while the first one is still pending stays a no-op")
  public void aDuplicateAgainstAPendingEntryIsDiscarded() throws Exception {

    final var aggregate = inTransaction(() -> workflowService.startWorkflow("still-pending"));
    listener.awaitInvocations(1, 30_000);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    // both are planned in ONE transaction, so nothing was dispatched in between: the
    // second one is discarded, and VanillaBP warns about it
    inTransaction(() -> {
      workflowService.correlateMessage(aggregate, "ItemShipped", "item-1");
      return workflowService.correlateMessage(aggregate, "ItemShipped", "item-1");
    });
    awaitCorrelations(1);
    Thread.sleep(1500);

    assertEquals(
        1,
        listener.getCorrelatedMessages().size(),
        "only one of the two was planned: "
            + listener.getCorrelatedMessages());

  }

  @Test
  @DisplayName("Completing and cancelling one task id are two operations now")
  public void completingAndCancellingOneTaskAreTwoOperations() throws Exception {

    final var aggregate = inTransaction(() -> workflowService.startWorkflow("task-key"));
    listener.awaitInvocations(1, 30_000);
    awareness.answerWith(WorkflowAwareness.ACTIVE);

    // one task id, two operations: they used to derive the same key, so the second one
    // was dropped although only one of the two can happen per task anyway
    inTransaction(() -> {
      workflowService.completeTask(aggregate, "task-1");
      return workflowService.cancelTask(aggregate, "task-1", "PAYMENT_FAILED");
    });

    final var deadline = System.currentTimeMillis() + 30_000;
    while (listener.getCompletedTasks().isEmpty() || listener.getCanceledTasks().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "both operations were expected; completed: "
              + listener.getCompletedTasks()
              + ", canceled: "
              + listener.getCanceledTasks());
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A second start of one aggregate after the first was dispatched starts again")
  public void aSecondStartAfterTheDispatchIsPlanned() throws Exception {

    final var aggregate = inTransaction(() -> workflowService.startWorkflow("second-start"));
    listener.awaitInvocations(1, 30_000);

    // an aggregate outliving its workflow and starting a second one of the same
    // process: while the first start was still pending this was a no-op, and it stays
    // one - after the dispatch it is a new operation
    inTransaction(() -> workflowService.startWorkflowAgain(aggregate));
    listener.awaitInvocations(2, 30_000);

    assertEquals(
        2,
        listener
            .getInvocations()
            .stream()
            .filter(id -> id.equals(aggregate.getId()))
            .count(),
        "both starts were dispatched: "
            + listener.getInvocations());

  }

}
