package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The outbox is open to extensions: an operation registered by
 * {@link SampleExtension} is scheduled inside the business transaction, dispatched
 * to the extension's own handler after the commit, deduplicated by the extension's
 * own idempotency key and retried when the handler fails - the same guarantees the
 * core operations get, without any core code knowing the operation.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class ExtensionOperationDispatchTest {

  /**
   * One entry, addressed by the key gruelbox stores it under, and only once gruelbox
   * marked it processed - the state in which that key stops deduplicating.
   */
  private static final String COUNT_PROCESSED_ENTRY_OF_KEY = "select count(*) from TXNO_OUTBOX "
      + "where processed = true and uniqueRequestId = ?";

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  /**
   * Waits until the entry of the given call stopped deduplicating.
   * <p>
   * {@link SampleExtension#awaitDispatched} reports that the handler was called, and
   * the handler runs INSIDE the dispatch - the entry is marked processed only after it
   * returned. Scheduling the same key in that window is discarded, so a test which
   * asserts that a repetition IS planned has to wait for the entry.
   */
  private void awaitDeduplicationWindowClosed(
      final Aggregate aggregate,
      final String event) throws Exception {

    final var key = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), event)
        .idempotencyKey()
        .orElseThrow();

    final var deadline = System.currentTimeMillis() + 10000;
    while (jdbcTemplate.queryForObject(COUNT_PROCESSED_ENTRY_OF_KEY, Long.class, key) == 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the entry of '%s' was never marked processed".formatted(key));
      Thread.sleep(50);
    }

  }

  private Aggregate startWorkflowAndSchedule(
      final String content,
      final String event) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      final var attached = processService.startWorkflow(aggregate);
      outbox
          .schedule(
              SampleExtension
                  .call("test-module", "dummy", attached.getId().toString(), event));
      return attached;
    });

  }

  @Test
  @DisplayName("An extension's operation is dispatched to the extension after the commit")
  public void extensionOperationIsDispatchedAfterCommit() throws Exception {

    final var aggregate = startWorkflowAndSchedule("extension-dispatch", "created");
    assertNotNull(aggregate);

    final var dispatched = extension.awaitDispatched(1, 10000);
    final var call = dispatched.getFirst();
    assertEquals(SampleExtension.OPERATION_NAME, call.operation());
    assertEquals(aggregate.getId().toString(), call.workflowAggregateId());
    // the arguments travel with the entry - the store persists them without ever
    // interpreting them
    assertEquals("created", call.args().get(SampleExtension.ARG_EVENT));

  }

  @Test
  @DisplayName("The extension's own idempotency key deduplicates its planned entries")
  public void extensionOperationIsDeduplicatedByItsOwnKey() throws Exception {

    // same aggregate, same event, and the first entry still waiting for its dispatch:
    // the key repeats, so scheduling is a no-op. Both ride ONE transaction on purpose -
    // this half of the test plans AGAINST a pending entry
    final var scheduledTwice = new java.util.concurrent.atomic.AtomicBoolean(true);
    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("extension-dedup");
      final var attached = processService.startWorkflow(newAggregate);
      outbox
          .schedule(
              SampleExtension.call("test-module", "dummy", attached.getId().toString(), "created"));
      scheduledTwice
          .set(outbox
              .schedule(
                  SampleExtension.call("test-module", "dummy", attached.getId().toString(), "created")));
      return attached;
    });
    assertNotNull(aggregate);
    assertFalse(scheduledTwice.get());

    extension.awaitDispatched(1, 10000);

    // a DIFFERENT event of the same workflow is a different key and is dispatched
    transactionTemplate.execute(status -> outbox
        .schedule(
            SampleExtension
                .call("test-module", "dummy", aggregate.getId().toString(), "completed")));

    final var dispatched = extension.awaitDispatched(2, 10000);
    assertEquals("created", dispatched.get(0).args().get(SampleExtension.ARG_EVENT));
    assertEquals("completed", dispatched.get(1).args().get(SampleExtension.ARG_EVENT));

    // and the very same event again, now that the first one reached the extension AND
    // its entry was marked processed: a new operation, because the key deduplicates
    // what is planned
    awaitDeduplicationWindowClosed(aggregate, "created");
    final var scheduledAfterDispatch = transactionTemplate
        .execute(status -> outbox
            .schedule(
                SampleExtension
                    .call("test-module", "dummy", aggregate.getId().toString(), "created")));
    assertTrue(Boolean.TRUE.equals(scheduledAfterDispatch));
    extension.awaitDispatched(3, 10000);

  }

  @Test
  @DisplayName("A failing extension dispatch is retried")
  public void failingExtensionDispatchIsRetried() throws Exception {

    extension.failNextDispatches(1);

    final var aggregate = startWorkflowAndSchedule("extension-retry", "created");
    assertNotNull(aggregate);

    // the first attempt threw, the retry succeeds
    final var dispatched = extension.awaitDispatched(1, 10000);
    assertEquals(aggregate.getId().toString(), dispatched.getFirst().workflowAggregateId());

  }

  @Test
  @DisplayName("On rollback the extension's entry is gone and never dispatched")
  public void rollbackLeavesNoExtensionEntry() throws Exception {

    try {
      transactionTemplate.execute(status -> {
        final var aggregate = new Aggregate();
        aggregate.setContent("extension-rollback");
        final var attached = processService.startWorkflow(aggregate);
        outbox
            .schedule(
                SampleExtension
                    .call("test-module", "dummy", attached.getId().toString(), "created"));
        throw new RuntimeException("test rollback");
      });
    } catch (final RuntimeException e) {
      assertEquals("test rollback", e.getMessage());
    }

    // wait longer than the poll interval: the entry rode the rolled-back
    // transaction, so nothing may ever be dispatched
    Thread.sleep(1500);
    assertTrue(extension.getDispatched().isEmpty());

  }

}
