package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Integration test of the JDBC/JTA-based phase-two outbox using the dummy adapter
 * forced to require a two-phase commit
 * (<code>dummy-adapter.two-phase-commit: true</code>):
 * <ul>
 *   <li>the outbox entry is enlisted in the local JTA transaction (visible within the
 *       transaction, gone on rollback),</li>
 *   <li>phase two is dispatched after the commit (with the aggregate ID converted back
 *       to its original type) and the entry is removed,</li>
 *   <li>a failing dispatch is retried.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxDispatchTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  private static final String COUNT_OUTBOX_ENTRIES = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX";

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private long countOutboxEntries() throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(COUNT_OUTBOX_ENTRIES)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  @Test
  @DisplayName("The outbox entry is written in the same transaction and phase two is dispatched after commit")
  public void entryWrittenInSameTransactionAndPhaseTwoDispatchedAfterCommit() throws Exception {

    final var entriesBefore = countOutboxEntries();

    userTransaction.begin();
    final Aggregate attachedAggregate;
    try {
      attachedAggregate = workflowService.startWorkflow("commit-test");
      // the outbox entry has to be visible within the still-running transaction:
      // the count query joins the active JTA transaction
      assertEquals(entriesBefore + 1, countOutboxEntries());
    } catch (Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();

    assertNotNull(attachedAggregate.getId());

    // after the commit, phase two has to be dispatched with the aggregate's ID
    // converted back from its string representation to the original type
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // the entry has to be removed after the successful dispatch
    final var deadline = System.currentTimeMillis() + 10000;
    while (countOutboxEntries() > entriesBefore) {
      assertTrue(System.currentTimeMillis() < deadline, "outbox entry was not removed");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("On rollback no outbox entry remains and phase two is never dispatched")
  public void rollbackLeavesNoEntryAndNoPhaseTwo() throws Exception {

    final var entriesBefore = countOutboxEntries();

    userTransaction.begin();
    workflowService.startWorkflow("rollback-test");
    // the outbox entry has to be visible within the still-running transaction
    assertEquals(entriesBefore + 1, countOutboxEntries());
    userTransaction.rollback();

    // the entry must be gone since it was enlisted in the rolled-back transaction
    assertEquals(entriesBefore, countOutboxEntries());

    // wait longer than the poll interval: phase two must never be dispatched
    Thread.sleep(1500);
    assertTrue(listener.getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A failing dispatch is retried")
  public void failingDispatchIsRetried() throws Exception {

    listener.failNextDispatches(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("retry-test");
    userTransaction.commit();

    // the first dispatch fails, the retry succeeds
    final var invocations = listener.awaitInvocations(2, 10000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));

  }

}
