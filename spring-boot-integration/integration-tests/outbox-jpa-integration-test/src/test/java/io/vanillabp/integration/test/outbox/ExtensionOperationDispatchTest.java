package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import io.vanillabp.spi.process.ProcessService;

/**
 * The outbox is open to extensions: an operation registered by
 * {@link SampleExtension} is scheduled inside the business transaction, dispatched
 * to the extension's own handler after the commit, deduplicated by the extension's
 * own idempotency key and retried when the handler fails - the same guarantees the
 * core operations get, without any core code knowing the operation.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class ExtensionOperationDispatchTest {

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
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @Autowired
  private DataSource dataSource;

  /**
   * What the outbox table holds, read through the transaction this test is running: the
   * payload of a call is asserted while the transaction which wrote it is still open.
   */
  private PhaseTwoOutboxReader outboxTable;

  @BeforeEach
  public void resetExtension() {

    extension.reset();
    outboxTable = PhaseTwoOutboxReader.ofTheVanillaBpOutbox(new TransactionAwareDataSourceProxy(dataSource));

  }

  /**
   * How many entries of that key were dispatched, which is the state in which a key
   * stops deduplicating.
   *
   * @param idempotencyKey The key asked about
   * @return The number of entries
   */
  private long dispatchedEntriesOf(
      final String idempotencyKey) {

    return outboxTable
        .entries()
        .stream()
        .filter(Entry::wasDispatched)
        .filter(entry -> idempotencyKey.equals(entry.idempotencyKey()))
        .count();

  }

  /**
   * How many payloads lie under that reference, which is one while the bytes of a call
   * wait and none once its entry was dispatched.
   *
   * @param reference The reference asked about
   * @return The number of payloads
   */
  private long payloadsOf(
      final String reference) {

    return outboxTable
        .payloads()
        .stream()
        .filter(payload -> reference.equals(payload.reference()))
        .count();

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
    while (dispatchedEntriesOf(key) == 0) {
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
  @DisplayName("A payload travels by reference and is gone once the entry was dispatched")
  public void aPayloadTravelsByReference() throws Exception {

    final var state = "{\"amount\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    final var reference = new java.util.concurrent.atomic.AtomicReference<String>();
    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("extension-payload");
      final var attached = processService.startWorkflow(newAggregate);
      final var call = SampleExtension
          .call("test-module", "dummy", attached.getId().toString(), "created", state);
      reference.set(call.payloadReference());
      outbox.schedule(call);
      // the bytes ride the very transaction the aggregate rides: they are there
      // already, and a rollback would take them with it
      assertEquals(1L, payloadsOf(call.payloadReference()));
      return attached;
    });
    assertNotNull(aggregate);

    final var dispatched = extension.awaitDispatched(1, 10000);
    final var call = dispatched.getFirst();
    assertArrayEquals(state, call.payload());
    assertEquals(reference.get(), call.payloadReference());

    // the entry was dispatched, so the bytes are gone - removed right after the entry was
    // marked DONE
    final var deadline = System.currentTimeMillis() + 10000;
    while (payloadsOf(reference.get()) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the payload '%s' was never removed".formatted(reference.get()));
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A call without a payload writes no row into the payload table")
  public void aCallWithoutAPayloadStoresNothing() throws Exception {

    final var before = outboxTable.payloads().size();

    final var aggregate = startWorkflowAndSchedule("extension-no-payload", "created");
    assertNotNull(aggregate);
    final var dispatched = extension.awaitDispatched(1, 10000);
    assertFalse(dispatched.getFirst().hasPayload());
    assertNull(dispatched.getFirst().payloadReference());

    assertEquals(before, outboxTable.payloads().size());

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
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(extension.getDispatched().isEmpty());

  }

}
