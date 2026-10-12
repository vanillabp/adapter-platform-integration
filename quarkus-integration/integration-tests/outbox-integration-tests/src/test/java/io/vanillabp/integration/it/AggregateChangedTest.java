package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.SteerableTaskAwarenessSource;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Pushing a changed workflow aggregate on Quarkus, with the dummy adapter forced to need a
 * two-phase commit. A REMOTE BPMS is written to after the local transaction was committed,
 * so the push travels in an outbox entry. This test pins three things. The task id travels
 * with the entry: the test sees it arrive at the adapter, it does not read the outbox table
 * for it. The entry carries no idempotency key, because the values are read at dispatch
 * time. And a rollback pushes nothing at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateChangedTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>, which these tests
   * set to half a second. So this is three of those windows.
   * <p>
   * It is a guard, not a measurement of speed. A machine which gives this JVM no turn only
   * makes the wait longer. Afterwards the test asserts a count which did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("aggregate-changed.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(SteerableTaskAwarenessSource.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:aggregate-changed-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  SteerableTaskAwarenessSource awareness;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  /**
   * The entries this test is about. The data source of a Quarkus application hands out a
   * connection of the running JTA transaction. So an entry can be counted while the
   * transaction which wrote it is still open.
   *
   * @return The entries of the push operation
   */
  private List<Entry> pushEntries() {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(entry -> PhaseOperation.AGGREGATE_CHANGED.name().equals(entry.operation()))
        .toList();

  }

  private Aggregate startedAggregate(
      final String content) throws Exception {

    awareness.answerWith(WorkflowAwareness.UNKNOWN_TO_BPMS);
    userTransaction.begin();
    try {
      final var aggregate = workflowService.startWorkflow(content);
      userTransaction.commit();
      listener.awaitInvocations(1, 30_000);
      awareness.answerWith(WorkflowAwareness.ACTIVE);
      return aggregate;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  @Test
  @DisplayName("Both scopes dispatch after the commit, and neither entry has an idempotency key")
  public void bothScopesDispatchAfterTheCommit() throws Exception {

    final var aggregate = startedAggregate("aggregate-changed");

    userTransaction.begin();
    try {
      workflowService.aggregateChanged(aggregate);
      workflowService.aggregateChanged(aggregate, "task-1");
      // repeating the very same push writes the state of that moment again. On purpose,
      // nothing is deduplicated
      workflowService.aggregateChanged(aggregate, "task-1");
      assertEquals(3, pushEntries().size());
      assertTrue(listener.getAggregateChanges().isEmpty(), "nothing may be pushed before the commit");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + 30_000;
    while (listener.getAggregateChanges().size() < 3) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the pushes were not dispatched in time: "
              + listener.getAggregateChanges());
      Thread.sleep(50);
    }

    assertEquals(
        1,
        listener
            .getAggregateChanges()
            .stream()
            .filter(entry -> entry.equals(aggregate.getId()
                + ":null:phase-two"))
            .count(),
        "the global push carries no task id");
    assertEquals(
        2,
        listener
            .getAggregateChanges()
            .stream()
            .filter(entry -> entry.equals(aggregate.getId()
                + ":task-1:phase-two"))
            .count(),
        "the task id travels, and identical pushes are not deduplicated");

    assertEquals(
        3,
        pushEntries()
            .stream()
            .filter(entry -> entry.idempotencyKey() == null)
            .count(),
        "a push is never deduplicated, so its entry carries no key");

  }

  @Test
  @DisplayName("On rollback the entry is gone and nothing is pushed")
  public void rollbackPushesNothing() throws Exception {

    final var aggregate = startedAggregate("rolled-back");
    final var entriesBefore = pushEntries().size();
    final var pushesBefore = listener.getAggregateChanges().size();

    userTransaction.begin();
    workflowService.aggregateChanged(aggregate);
    assertEquals(entriesBefore + 1, pushEntries().size());
    userTransaction.rollback();

    assertEquals(entriesBefore, pushEntries().size());

    // wait longer than the poll interval: nothing may ever be pushed
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(pushesBefore, listener.getAggregateChanges().size());

  }

}
