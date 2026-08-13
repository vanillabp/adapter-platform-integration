package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

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
 * Pushing a changed workflow-aggregate on Quarkus (story 44) with the dummy adapter
 * forced to require a two-phase commit: a REMOTE BPMS is written to after the local
 * transaction was committed, so the push rides an outbox entry. What this pins: the
 * task id travels in the entry's ARGS, the entry carries no idempotency key (the
 * values are read at dispatch time) and a rollback pushes nothing at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateChangedTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("aggregate-changed.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(SteerableTaskAwarenessSource.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  private static final String COUNT_ENTRIES = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE OPERATION = 'AGGREGATE_CHANGED'";

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

  private long count(
      final String query) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(query)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  private Aggregate startedAggregate(
      final String content) throws Exception {

    awareness.answerWith(WorkflowAwareness.UNKNOWN_TO_BPMS);
    userTransaction.begin();
    try {
      final var aggregate = workflowService.startWorkflow(content);
      userTransaction.commit();
      listener.awaitInvocations(1, 10000);
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
      // repeating the very same push writes the then-current state again - nothing
      // is deduplicated, on purpose
      workflowService.aggregateChanged(aggregate, "task-1");
      assertEquals(3, count(COUNT_ENTRIES));
      assertTrue(listener.getAggregateChanges().isEmpty(), "nothing may be pushed before the commit");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + 15000;
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
        count(
            "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX WHERE OPERATION = 'AGGREGATE_CHANGED' "
                + "AND IDEMPOTENCY_KEY IS NULL"));

  }

  @Test
  @DisplayName("On rollback the entry is gone and nothing is pushed")
  public void rollbackPushesNothing() throws Exception {

    final var aggregate = startedAggregate("rolled-back");
    final var entriesBefore = count(COUNT_ENTRIES);
    final var pushesBefore = listener.getAggregateChanges().size();

    userTransaction.begin();
    workflowService.aggregateChanged(aggregate);
    assertEquals(entriesBefore + 1, count(COUNT_ENTRIES));
    userTransaction.rollback();

    assertEquals(entriesBefore, count(COUNT_ENTRIES));

    // wait longer than the poll interval: nothing may ever be pushed
    Thread.sleep(1500);
    assertEquals(pushesBefore, listener.getAggregateChanges().size());

  }

}
