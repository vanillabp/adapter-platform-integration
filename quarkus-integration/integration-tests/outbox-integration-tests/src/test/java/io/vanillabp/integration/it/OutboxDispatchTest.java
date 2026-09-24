package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Integration test of the JDBC/JTA-based phase-two outbox using the dummy adapter
 * forced to require a two-phase commit
 * (<code>dummy-adapter.at-least-once-delivery: true</code>):
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

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>, which these tests
   * configure as half a second, so this is three of those windows.
   * <p>
   * It is a guard and not a measurement of speed: a machine which leaves this JVM without
   * a turn only makes the wait longer, and what is asserted afterwards is a count which
   * did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-dispatch-it;DB_CLOSE_DELAY=-1");

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

  /**
   * What the outbox table holds. The data source of a Quarkus application hands out a
   * connection of the running JTA transaction, so an entry is counted while the
   * transaction which wrote it is still open.
   *
   * @return All entries
   */
  private List<Entry> outboxEntries() {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries();

  }

  private long countOutboxEntries() {

    return outboxEntries().size();

  }

  /**
   * @param aggregateId The aggregate asked about
   * @return Its entries
   */
  private List<Entry> entriesOf(
      final Object aggregateId) {

    return outboxEntries()
        .stream()
        .filter(entry -> aggregateId.toString().equals(entry.aggregateId()))
        .toList();

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
    final var invocations = listener.awaitInvocations(1, 30_000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // DONE instead of delete: the entry has to be marked DONE after the successful
    // dispatch and stays visible until the asynchronous retention cleanup
    final var deadline = System.currentTimeMillis() + 30_000;
    while (entriesOf(attachedAggregate.getId()).stream().noneMatch(Entry::wasDispatched)) {
      assertTrue(System.currentTimeMillis() < deadline, "outbox entry was not marked DONE");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A duplicate schedule while the first one is still pending is a no-op")
  public void duplicateScheduleAgainstAPendingEntryIsNoOp() throws Exception {

    // both starts ride ONE transaction, so nothing is dispatched in between and the
    // second one meets an entry which is still waiting: the unique constraint over
    // DEDUP_KEY makes it a no-op. What happens after the dispatch is
    // RepeatedOperationTest's subject
    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("dedup-pending");
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    listener.awaitInvocations(1, 30_000);

    // wait longer than the poll interval: no second dispatch may happen
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(1, listener.getInvocations().size(), "only one of the two starts was planned");
    assertEquals(1, entriesOf(attachedAggregate.getId()).size());

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
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
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
    final var invocations = listener.awaitInvocations(2, 30_000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));

  }

}
