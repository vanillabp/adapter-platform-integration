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

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

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
    // the key repeats, so scheduling is a no-op
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

    // and the very same event again, now that the first one reached the extension: a
    // new operation, because the key deduplicates what is planned
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
