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
 * (<code>dummy-adapter.two-phase-commit: true</code>):
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
