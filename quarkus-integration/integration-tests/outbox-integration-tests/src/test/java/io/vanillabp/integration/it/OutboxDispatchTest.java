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
 *       to its original type) and the entry is marked DONE (deleted asynchronously
 *       after the retention period only - see {@link OutboxRetentionTest}),</li>
 *   <li>a duplicate schedule for the same aggregate is a no-op (unique idempotency
 *       key) and</li>
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

  private static final String COUNT_DONE_ENTRIES_OF_AGGREGATE = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE AGGREGATE_ID = '%s' AND STATUS = 'DONE'";

  private static final String COUNT_ENTRIES_OF_AGGREGATE = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE AGGREGATE_ID = '%s'";

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

    return count(COUNT_OUTBOX_ENTRIES);

  }

  private long count(
      final String query) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(query)) {
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

    // DONE instead of delete: the entry has to be marked DONE after the successful
    // dispatch and stays visible until the asynchronous retention cleanup
    final var deadline = System.currentTimeMillis() + 10000;
    while (count(COUNT_DONE_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "outbox entry was not marked DONE");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A duplicate schedule for the same aggregate is a no-op (unique idempotency key)")
  public void duplicateScheduleIsNoOp() throws Exception {

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("dedup-test");
    userTransaction.commit();

    listener.awaitInvocations(1, 10000);

    // starting the workflow again for the same aggregate schedules the same
    // idempotency key: the unique constraint makes it a no-op - no second entry,
    // no second dispatch (the DONE entry keeps the deduplication window open)
    userTransaction.begin();
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    // wait longer than the poll interval: no second dispatch may happen
    Thread.sleep(1500);
    assertEquals(1, listener.getInvocations().size());
    assertEquals(1, count(COUNT_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())));

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
