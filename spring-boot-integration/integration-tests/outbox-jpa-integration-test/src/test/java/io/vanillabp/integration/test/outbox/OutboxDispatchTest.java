package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import io.vanillabp.spi.process.ProcessService;

/**
 * Integration test of the JDBC phase-two outbox on JPA using the dummy adapter
 * forced to require a two-phase commit
 * (<code>dummy-adapter.at-least-once-delivery: true</code>):
 * <ul>
 *   <li>the outbox entry is enlisted in the local transaction persisting the
 *       aggregate (gone on rollback),</li>
 *   <li>phase two is dispatched after the commit (with the aggregate ID converted back
 *       to its original type) and</li>
 *   <li>a failing dispatch is retried.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class OutboxDispatchTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>. These tests set that
   * to half a second, so this is three of those windows.
   * <p>
   * It is a guard, not a measurement of speed. A machine which leaves this JVM without a
   * turn only makes the wait longer. Afterwards the test asserts a count which did not
   * grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private long countOutboxEntries() {

    return outboxTable().entries().size();

  }

  /**
   * What the outbox table holds. It is read outside the transaction of this test, as the
   * rollback case below needs. An entry becomes visible when the transaction which wrote it
   * commits.
   *
   * @return The reader of the table
   */
  private PhaseTwoOutboxReader outboxTable() {

    return PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

  }

  /**
   * The entries of ONE aggregate which were dispatched. In this state their key stops
   * deduplicating. A count over the whole table would already be satisfied by an entry of
   * another test. That would hide the same mistake.
   *
   * @param aggregateId The aggregate asked about
   * @return The number of entries
   */
  private long dispatchedEntriesOf(
      final Object aggregateId) {

    return outboxTable()
        .entries()
        .stream()
        .filter(Entry::wasDispatched)
        .filter(entry -> aggregateId.toString().equals(entry.aggregateId()))
        .count();

  }

  @Test
  @DisplayName("The outbox entry is written in the same transaction and phase two is dispatched after commit")
  public void entryWrittenInSameTransactionAndPhaseTwoDispatchedAfterCommit() throws Exception {

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("commit-test");
      return processService.startWorkflow(aggregate);
      // note: the entry is written on the connection of this transaction, so nobody
      // outside can see it yet. The rollback test proves instead that the entry is part
      // of the transaction
    });

    assertNotNull(attachedAggregate);
    assertNotNull(attachedAggregate.getId());

    // after the commit, phase two must be dispatched. The ID of the aggregate is
    // converted back from its string form to the original type
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

  }

  @Test
  @DisplayName("On rollback no outbox entry remains and phase two is never dispatched")
  public void rollbackLeavesNoEntryAndNoPhaseTwo() throws Exception {

    final var entriesBefore = countOutboxEntries();

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("rollback-test");
          processService.startWorkflow(aggregate);
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    // the entry must be gone, because it was part of the rolled-back transaction
    assertEquals(entriesBefore, countOutboxEntries());

    // wait longer than the poll interval: phase two must never be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(listener.getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A duplicate schedule while the first one is still pending is a no-op")
  public void duplicateScheduleAgainstAPendingEntryIsNoOp() {

    // the dispatcher must not empty the outbox while both are scheduled. So both starts
    // run in ONE transaction
    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("dedup-pending");
      final var started = processService.startWorkflow(aggregate);
      // the same idempotency key while nothing was dispatched. The unique DEDUP_KEY of
      // the waiting entry makes it a no-op
      return processService.startWorkflow(started);
    });
    assertNotNull(attachedAggregate);

    final var invocationsOfThisAggregate = countStartsOf(attachedAggregate.getId(), 1);
    assertEquals(1, invocationsOfThisAggregate, "only one of the two starts was planned");

  }

  @Test
  @DisplayName("A repetition after the dispatch is a new operation - the key does not block it")
  public void aRepetitionAfterTheDispatchIsPlanned() throws Exception {

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("dedup-dispatched");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);
    listener.awaitInvocations(1, 10000);

    // DONE instead of delete: the dispatched entry stays in the table until the retention
    // passes. Its key is released when it is marked DONE. The test waits for the ENTRY,
    // not for the listener. Only then does the next schedule meet the state this test is
    // about. The listener runs inside the dispatch, before the entry is processed
    final var deadline = System.currentTimeMillis() + 10000;
    while (dispatchedEntriesOf(attachedAggregate.getId()) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "processed outbox entry was not retained");
      Thread.sleep(50);
    }

    // the key deduplicates what is PLANNED. So the retained entry is released, and the
    // second start is carried out (see decision 22 in the repository's DECISIONS.md)
    transactionTemplate.execute(status -> processService.startWorkflow(attachedAggregate));

    assertEquals(
        2,
        countStartsOf(attachedAggregate.getId(), 2),
        "the repetition after the dispatch reached the BPMS: "
            + listener.getInvocations());

  }

  /**
   * How often phase two of a start ran for the given aggregate. It waits until at least
   * the expected number arrived, and then a little longer. So a further dispatch would
   * still show up.
   */
  private long countStartsOf(
      final Object aggregateId,
      final int expected) {

    final var deadline = System.currentTimeMillis() + 10000;
    while (startsOf(aggregateId) < expected) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected %d start(s) of aggregate '%s' but got %s".formatted(expected, aggregateId, listener
              .getInvocations()));
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    try {
      Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return startsOf(aggregateId);

  }

  private long startsOf(
      final Object aggregateId) {

    return listener
        .getInvocations()
        .stream()
        .filter(aggregateId::equals)
        .count();

  }

  @Test
  @DisplayName("A failing dispatch is retried")
  public void failingDispatchIsRetried() throws Exception {

    listener.failNextDispatches(1);

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("retry-test");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);

    // the first dispatch fails, the retry succeeds
    final var invocations = listener.awaitInvocations(2, 10000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));

  }

}
