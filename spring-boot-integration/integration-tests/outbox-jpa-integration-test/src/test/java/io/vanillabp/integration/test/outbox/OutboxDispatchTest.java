package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Integration test of the gruelbox-based JPA phase-two outbox using the dummy adapter
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
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OutboxDispatchTest {

  private static final String COUNT_OUTBOX_ENTRIES = "select count(*) from TXNO_OUTBOX";

  /**
   * The entry of ONE aggregate, and only once gruelbox marked it processed - the state
   * in which its key stops deduplicating. A count over the whole table would already be
   * satisfied by a sibling test's entry, which is the same mistake in a hiding place.
   * The key of a start ends in the aggregate's ID (see
   * {@code PhaseTwoOperation#START_WORKFLOW}).
   */
  private static final String COUNT_PROCESSED_START_OF_AGGREGATE = "select count(*) from TXNO_OUTBOX "
      + "where processed = true and uniqueRequestId like '%%|%s'";

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private long countOutboxEntries() {

    final var count = jdbcTemplate.queryForObject(COUNT_OUTBOX_ENTRIES, Long.class);
    return count == null ? 0 : count;

  }

  @Test
  @DisplayName("The outbox entry is written in the same transaction and phase two is dispatched after commit")
  public void entryWrittenInSameTransactionAndPhaseTwoDispatchedAfterCommit() throws Exception {

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("commit-test");
      return processService.startWorkflow(aggregate);
      // note: the outbox entry cannot be observed within the running transaction
      // since gruelbox writes it in a JDBC batch right before the commit - the
      // transactional enlisting is proven by the rollback test instead
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
    Thread.sleep(1500);
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
      // the same idempotency key while nothing was dispatched: gruelbox' unique
      // request ID makes it a no-op
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

    // DONE instead of delete: gruelbox retains the processed entry (unique request
    // ID + retention threshold), which used to keep the deduplication window open.
    // Waiting for the ENTRY and not for the listener is what makes the next schedule
    // meet the state this test is about - the listener runs inside the dispatch, before
    // the entry is processed
    final var deadline = System.currentTimeMillis() + 10000;
    while (jdbcTemplate
        .queryForObject(
            COUNT_PROCESSED_START_OF_AGGREGATE.formatted(attachedAggregate.getId()),
            Long.class) == 0) {
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
      Thread.sleep(1500);
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
