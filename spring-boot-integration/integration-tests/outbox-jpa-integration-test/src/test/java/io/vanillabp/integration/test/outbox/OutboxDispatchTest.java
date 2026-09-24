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
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>, which these tests
   * configure as half a second, so this is three of those windows.
   * <p>
   * It is a guard and not a measurement of speed: a machine which leaves this JVM without
   * a turn only makes the wait longer, and what is asserted afterwards is a count which
   * did not grow.
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
   * What the outbox table holds. It is read outside the transaction of this test, which
   * is what the rollback case below asks for: an entry becomes visible when the
   * transaction which wrote it commits.
   *
   * @return The reader of the table
   */
  private PhaseTwoOutboxReader outboxTable() {

    return PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

  }

  /**
   * The entries of ONE aggregate which were dispatched - the state in which their key
   * stops deduplicating. A count over the whole table would already be satisfied by a
   * sibling test's entry, which is the same mistake in a hiding place.
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
      // note: the entry is written on the connection of this very transaction, so
      // nobody outside it can see it yet - the transactional enlisting is proven by the
      // rollback test instead
    });

    assertNotNull(attachedAggregate);
    assertNotNull(attachedAggregate.getId());

    // after the commit, phase two has to be dispatched with the aggregate's ID
    // converted back from its string representation to the original type
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

    // the entry must be gone since it was enlisted in the rolled-back transaction
    assertEquals(entriesBefore, countOutboxEntries());

    // wait longer than the poll interval: phase two must never be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(listener.getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A duplicate schedule while the first one is still pending is a no-op")
  public void duplicateScheduleAgainstAPendingEntryIsNoOp() {

    // the dispatcher must not empty the outbox while both are scheduled, so both
    // starts ride ONE transaction
    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("dedup-pending");
      final var started = processService.startWorkflow(aggregate);
      // the same idempotency key while nothing was dispatched: the unique DEDUP_KEY
      // of the waiting entry makes it a no-op
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

    // DONE instead of delete: the dispatched entry stays in the table until the
    // retention passes, and its key is released when it is marked DONE.
    // Waiting for the ENTRY and not for the listener is what makes the next schedule
    // meet the state this test is about - the listener runs inside the dispatch, before
    // the entry is processed
    final var deadline = System.currentTimeMillis() + 10000;
    while (dispatchedEntriesOf(attachedAggregate.getId()) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "processed outbox entry was not retained");
      Thread.sleep(50);
    }

    // the key deduplicates what is PLANNED, so the retained entry is released and the
    // second start is carried out (see decision 22 in the repository's DECISIONS.md)
    transactionTemplate.execute(status -> processService.startWorkflow(attachedAggregate));

    assertEquals(
        2,
        countStartsOf(attachedAggregate.getId(), 2),
        "the repetition after the dispatch reached the BPMS: "
            + listener.getInvocations());

  }

  /**
   * How often phase two of a start ran for the given aggregate, waiting until at least
   * the expected number arrived and then a poll interval longer, so a third dispatch
   * would still show up.
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
