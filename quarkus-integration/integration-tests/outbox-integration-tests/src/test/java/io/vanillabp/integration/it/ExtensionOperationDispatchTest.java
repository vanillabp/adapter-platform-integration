package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.SampleExtension;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The outbox is open to extensions: an operation registered by
 * {@link SampleExtension} is scheduled inside the business transaction, dispatched
 * to the extension's own handler after the commit, deduplicated by the extension's
 * own idempotency key and retried when the handler fails - the same guarantees the
 * core operations get, without any core code knowing the operation.
 */
@ExtendWith(SuppressOutputExtension.class)
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

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          // an own database: the sibling tests of this module count rows of the
          // outbox store they share within the JVM
          .addAsResource("extension-operation.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(SampleExtension.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:extension-operation-dispatch-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  SampleExtension extension;

  @Inject
  PhaseTwoOutbox outbox;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  /**
   * What the outbox table holds. The data source of a Quarkus application hands out a
   * connection of the running JTA transaction, so an entry is read while the transaction
   * which wrote it is still open.
   *
   * @return The reader of the table
   */
  private PhaseTwoOutboxReader outboxTable() {

    return PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

  }

  /**
   * @return The entries of the extension's operation
   */
  private List<Entry> entriesOfTheOperation() {

    return outboxTable()
        .entries()
        .stream()
        .filter(entry -> SampleExtension.OPERATION_NAME.equals(entry.operation()))
        .toList();

  }

  /**
   * An entry deduplicates as long as it was not dispatched, and the dispatcher marks it
   * one write after the extension's handler returned.
   *
   * @param aggregateId The aggregate asked about
   * @return How many entries of that aggregate still deduplicate
   */
  private long entriesStillDeduplicating(
      final Object aggregateId) {

    return entriesOfTheOperation()
        .stream()
        .filter(entry -> aggregateId.toString().equals(entry.aggregateId()))
        .filter(entry -> !entry.wasDispatched())
        .count();

  }

  /**
   * @param idempotencyKey The key asked about
   * @return How many entries still carry it, which is what a second schedule of that key
   *         meets
   */
  private long entriesOfKey(
      final String idempotencyKey) {

    return outboxTable()
        .entries()
        .stream()
        .filter(entry -> !entry.wasDispatched())
        .filter(entry -> idempotencyKey.equals(entry.idempotencyKey()))
        .count();

  }

  /**
   * @param aggregateId The aggregate asked about
   * @return How many of its entries are waiting, whatever they deduplicate against
   */
  private long waitingEntriesOf(
      final Object aggregateId) {

    return outboxTable()
        .entries()
        .stream()
        .filter(Entry::isWaiting)
        .filter(entry -> aggregateId.toString().equals(entry.aggregateId()))
        .count();

  }

  /**
   * @param reference The reference asked about
   * @return How many payloads lie under it
   */
  private long payloadsOf(
      final String reference) {

    return outboxTable()
        .payloads()
        .stream()
        .filter(payload -> reference.equals(payload.reference()))
        .count();

  }

  /**
   * Waits until no entry of the extension's operation deduplicates any more.
   * <p>
   * {@link SampleExtension#awaitDispatched} reports that the handler was called, and
   * the handler is called INSIDE the dispatch - the entry still carries its key until
   * the dispatcher marks it DONE. Scheduling the same key in that window is discarded,
   * so a test which asserts that a repetition IS planned has to wait for the entry.
   */
  private void awaitDeduplicationWindowClosed(
      final Aggregate aggregate) throws Exception {

    final var deadline = System.currentTimeMillis() + 10000;
    while (entriesStillDeduplicating(aggregate.getId()) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "an entry of the extension's operation was never marked DONE");
      Thread.sleep(50);
    }

  }

  private Aggregate startWorkflowAndSchedule(
      final String content,
      final String event) throws Exception {

    userTransaction.begin();
    final Aggregate attached;
    try {
      attached = workflowService.startWorkflow(content);
      outbox
          .schedule(
              SampleExtension.call("test-module", "dummy", attached.getId().toString(), event));
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();
    return attached;

  }

  private static byte[] payloadOf(
      final String content) {

    return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

  }

  @Test
  @DisplayName("The younger report replaces the waiting one, and only it is dispatched")
  public void theYoungerReportReplacesTheWaitingOne() throws Exception {

    // both ride ONE transaction on purpose: nothing is dispatched before it commits,
    // so the second call meets an entry which is certainly still waiting
    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("replace-jdbc");
    final var first = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), "replaced", payloadOf("{\"amount\":1}"));
    assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(first));
    final var second = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), "replaced", payloadOf("{\"amount\":2}"));
    assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(second));

    // one entry under that key, and the bytes of the replaced call are gone before
    // this transaction commits
    assertEquals(1L, entriesOfKey(first.idempotencyKey().orElseThrow()));
    assertEquals(0L, payloadsOf(first.payloadReference()));
    assertEquals(1L, payloadsOf(second.payloadReference()));
    userTransaction.commit();

    final var dispatched = extension.awaitDispatched(1, 10000);
    assertArrayEquals(payloadOf("{\"amount\":2}"), dispatched.getFirst().payload());
    assertEquals(second.payloadReference(), dispatched.getFirst().payloadReference());

    // and the one which was dispatched is the only one there ever was
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(1, extension.getDispatched().size());

  }

  @Test
  @DisplayName("Without the word the older report stays and the younger one is dropped")
  public void withoutTheWordTheOlderReportStays() throws Exception {

    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("no-replace-jdbc");
    final var first = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), "kept", payloadOf("{\"amount\":1}"));
    assertTrue(outbox.schedule(first));
    final var second = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), "kept", payloadOf("{\"amount\":2}"));
    assertFalse(outbox.schedule(second));
    // a schedule which was discarded leaves nothing behind
    assertEquals(0L, payloadsOf(second.payloadReference()));
    userTransaction.commit();

    final var dispatched = extension.awaitDispatched(1, 10000);
    assertArrayEquals(payloadOf("{\"amount\":1}"), dispatched.getFirst().payload());
    assertEquals(first.payloadReference(), dispatched.getFirst().payloadReference());

  }

  @Test
  @DisplayName("An entry a dispatch has taken is not replaced - the younger call becomes a second one")
  public void anEntryADispatchHasTakenIsNotReplaced() throws Exception {

    extension.holdNextDispatch();
    try {
      userTransaction.begin();
      final var aggregate = workflowService.startWorkflow("claimed-jdbc");
      assertTrue(
          outbox
              .scheduleReplacingWhatIsStillWaiting(
                  SampleExtension
                      .call(
                          "test-module",
                          "dummy",
                          aggregate.getId().toString(),
                          "claimed",
                          payloadOf("{\"amount\":1}"))));
      userTransaction.commit();

      // the entry is claimed and its dispatch stands inside the handler
      extension.awaitHeldDispatchEntered(10000);

      userTransaction.begin();
      assertTrue(
          outbox
              .scheduleReplacingWhatIsStillWaiting(
                  SampleExtension
                      .call(
                          "test-module",
                          "dummy",
                          aggregate.getId().toString(),
                          "claimed",
                          payloadOf("{\"amount\":2}"))));
      userTransaction.commit();

      // two entries wait now: the one which is on its way and the one this call became
      assertEquals(2L, waitingEntriesOf(aggregate.getId()));
    } finally {
      extension.releaseHeldDispatch();
    }

    // both reach the handler - which of them first is the dispatcher's business
    final var payloads = extension
        .awaitDispatched(2, 10000)
        .stream()
        .map(call -> new String(call.payload(), java.nio.charset.StandardCharsets.UTF_8))
        .toList();
    assertTrue(payloads.contains("{\"amount\":1}"), payloads.toString());
    assertTrue(payloads.contains("{\"amount\":2}"), payloads.toString());

  }

  @Test
  @DisplayName("A rolled-back replacement leaves neither entry nor payload behind")
  public void aRolledBackReplacementLeavesNothing() throws Exception {

    // the replaced entry has to be committed for this to be a replacement at all, and
    // the only moment it is certainly still waiting is before the transaction commits
    // which wrote it - so both calls ride the transaction which rolls back. What it
    // proves is that all three writes of a replacement enlist: the entry, the younger
    // payload, and the removal of the payload which was replaced
    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("rollback-jdbc");
    final var first = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), "rolled-back", payloadOf("{\"amount\":1}"));
    assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(first));
    final var second = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), "rolled-back", payloadOf("{\"amount\":2}"));
    assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(second));
    userTransaction.rollback();

    assertEquals(0L, entriesOfKey(first.idempotencyKey().orElseThrow()));
    assertEquals(0L, payloadsOf(first.payloadReference()));
    assertEquals(0L, payloadsOf(second.payloadReference()));

    // wait longer than the poll interval: nothing of that transaction may be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(extension.getDispatched().isEmpty());

  }

  @Test
  @DisplayName("A payload travels by reference and is gone once the entry was dispatched")
  public void aPayloadTravelsByReference() throws Exception {

    final var state = "{\"amount\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("extension-payload");
    final var scheduled = SampleExtension
        .call("test-module", "dummy", aggregate.getId().toString(), "created", state);
    outbox.schedule(scheduled);
    // the bytes ride the very transaction the aggregate rides: they are there already,
    // and a rollback would take them with it
    assertEquals(1L, payloadsOf(scheduled.payloadReference()));
    userTransaction.commit();

    final var dispatched = extension.awaitDispatched(1, 10000);
    final var call = dispatched.getFirst();
    assertArrayEquals(state, call.payload());
    assertEquals(scheduled.payloadReference(), call.payloadReference());

    // the entry was dispatched, so the bytes are gone
    final var deadline = System.currentTimeMillis() + 10000;
    while (payloadsOf(scheduled.payloadReference()) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the payload '%s' was never removed".formatted(scheduled.payloadReference()));
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A call without a payload writes no row into the payload table")
  public void aCallWithoutAPayloadStoresNothing() throws Exception {

    final var before = outboxTable().payloads().size();

    final var aggregate = startWorkflowAndSchedule("extension-no-payload", "created");
    assertNotNull(aggregate.getId());
    final var dispatched = extension.awaitDispatched(1, 10000);
    assertFalse(dispatched.getFirst().hasPayload());
    assertNull(dispatched.getFirst().payloadReference());

    assertEquals(before, outboxTable().payloads().size());

  }

  @Test
  @DisplayName("An extension's operation is dispatched to the extension after the commit")
  public void extensionOperationIsDispatchedAfterCommit() throws Exception {

    final var aggregate = startWorkflowAndSchedule("extension-dispatch", "created");
    assertNotNull(aggregate.getId());

    final var dispatched = extension.awaitDispatched(1, 10000);
    final var call = dispatched.getFirst();
    assertEquals(SampleExtension.OPERATION_NAME, call.operation());
    assertEquals(aggregate.getId().toString(), call.workflowAggregateId());
    // the arguments travel with the entry - the store persists them without ever
    // interpreting them
    assertEquals("created", call.args().get(SampleExtension.ARG_EVENT));

    // the entry is stored under the extension's operation NAME (the store knows
    // nothing else about it)
    assertTrue(entriesOfTheOperation().size() > 0);

  }

  @Test
  @DisplayName("The extension's own idempotency key deduplicates its planned entries")
  public void extensionOperationIsDeduplicatedByItsOwnKey() throws Exception {

    // same aggregate, same event, and the first entry still waiting for its dispatch:
    // the key repeats, so scheduling is a no-op. Both ride ONE transaction on purpose -
    // this half of the test plans AGAINST a pending entry
    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("extension-dedup");
    outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "created"));
    final var scheduledAgain = outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "created"));
    userTransaction.commit();
    assertFalse(scheduledAgain);

    extension.awaitDispatched(1, 10000);

    // a DIFFERENT event of the same workflow is a different key and is dispatched
    userTransaction.begin();
    outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "completed"));
    userTransaction.commit();

    final var dispatched = extension.awaitDispatched(2, 10000);
    assertEquals("created", dispatched.get(0).args().get(SampleExtension.ARG_EVENT));
    assertEquals("completed", dispatched.get(1).args().get(SampleExtension.ARG_EVENT));

    // and the very same event again, now that the first one reached the extension AND
    // its entry was marked DONE: a new operation, because the key deduplicates what is
    // planned
    awaitDeduplicationWindowClosed(aggregate);
    userTransaction.begin();
    final var scheduledAfterDispatch = outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "created"));
    userTransaction.commit();
    assertTrue(scheduledAfterDispatch);
    extension.awaitDispatched(3, 10000);

  }

  @Test
  @DisplayName("A failing extension dispatch is retried")
  public void failingExtensionDispatchIsRetried() throws Exception {

    extension.failNextDispatches(1);

    final var aggregate = startWorkflowAndSchedule("extension-retry", "created");

    // the first attempt threw, the retry succeeds
    final var dispatched = extension.awaitDispatched(1, 10000);
    assertEquals(aggregate.getId().toString(), dispatched.getFirst().workflowAggregateId());

  }

  @Test
  @DisplayName("On rollback the extension's entry is gone and never dispatched")
  public void rollbackLeavesNoExtensionEntry() throws Exception {

    final var entriesBefore = entriesOfTheOperation().size();

    userTransaction.begin();
    final var attached = workflowService.startWorkflow("extension-rollback");
    outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", attached.getId().toString(), "rolled-back"));
    userTransaction.rollback();

    // the entry rode the rolled-back transaction
    assertEquals(entriesBefore, entriesOfTheOperation().size());

    // wait longer than the poll interval: nothing may ever be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(extension.getDispatched().isEmpty());

  }

}
