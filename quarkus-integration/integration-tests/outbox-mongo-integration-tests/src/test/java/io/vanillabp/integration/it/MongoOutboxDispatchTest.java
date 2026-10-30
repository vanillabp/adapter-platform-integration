package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Integration test of the MongoDB-based phase-two outbox on Quarkus using the dummy
 * adapter, which plays a BPMS that repeats deliveries
 * (<code>dummy-adapter.at-least-once-delivery: true</code>), against a MongoDB started by
 * Quarkus Dev Services. That MongoDB is a single-node replica set. But this application has
 * no MongoDB Panache, so there is no MongoDB session to join. The outbox runs in its
 * best-effort mode (see {@code MongoPhaseTwoOutbox}):
 * <ul>
 *   <li>the entry is written immediately (best-effort: visible before the commit)
 *       and phase two is dispatched after the commit, marking the entry DONE
 *       (deleted asynchronously after the retention period only),</li>
 *   <li>on rollback the already-written entry is deleted best-effort and phase two
 *       is never dispatched,</li>
 *   <li>a duplicate schedule for the same aggregate is a no-op (unique index on
 *       <code>dedupKey</code>),</li>
 *   <li>a failing dispatch is retried and</li>
 *   <li>a left-over entry (like after a crash) is dispatched by the recovery
 *       poller and</li>
 *   <li>the store says how long the oldest entry waiting for its dispatch has been
 *       waiting.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOutboxDispatchTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>. These tests configure
   * it as half a second, so this is three of those windows.
   * <p>
   * It is a guard and does not measure speed. A machine which gives this JVM no turn for a
   * while only makes the wait longer. Afterwards the test asserts a count which did not grow.
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
      .overrideConfigKey("quarkus.mongodb.database", "outbox-dispatch-it");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  @Inject
  io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutbox outbox;

  /**
   * The outbox of the application. It is asked through the reader, so this test names
   * neither a collection nor a field.
   */
  private MongoPhaseTwoOutboxReader outbox() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase("outbox-dispatch-it"));

  }

  /**
   * Waits until no entry deduplicates any more.
   * <p>
   * The listener runs INSIDE the dispatch, one update before the dispatcher sets the status
   * to DONE and frees the dedup key. A repetition planned in that window meets an entry which
   * is still waiting, and it is discarded. That is correct. But it is the reason why a test
   * may not take the listener as the signal that the first operation is over.
   */
  private void awaitDeduplicationWindowClosed() throws Exception {

    final var deadline = System.currentTimeMillis() + 10000;
    while (outbox().entriesAtAll() > outbox().entriesDispatched()) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never marked DONE");
      Thread.sleep(50);
    }

  }

  @BeforeEach
  public void reset() {

    listener.reset();
    outbox().removeAllEntries();

  }

  @Test
  @DisplayName("The age of the oldest waiting entry is the one of the oldest, not of the youngest")
  public void theOldestWaitingEntryDecidesTheAge() {

    assertEquals(
        java.time.Duration.ZERO,
        outbox
            .ageOfOldestPendingCall()
            .orElseThrow(),
        "nothing waits, and that zero says the outbox owes nothing");

    final var now = java.time.Instant.now();
    writeWaitingEntry("younger", now.minus(java.time.Duration.ofMinutes(1)));
    writeWaitingEntry("older", now.minus(java.time.Duration.ofMinutes(10)));

    assertTrue(
        outbox
            .ageOfOldestPendingCall()
            .orElseThrow()
            .toSeconds() >= 600L,
        "the store answers for the entry which has been waiting longest");

  }

  @Test
  @DisplayName("The store says whether an entry of one workflow waits, also inside the transaction which wrote it")
  public void theStoreSaysWhetherAnEntryOfAWorkflowWaits() throws Exception {

    writeWaitingEntry("waiting", java.time.Instant.now());

    assertEquals(java.util.Optional.of(true), outbox.hasPendingCallsOf("test-module", "Test", "4711"));
    assertEquals(
        java.util.Optional.of(false),
        outbox.hasPendingCallsOf("test-module", "Test", "4712"),
        "an entry of another workflow does not count");

    userTransaction.begin();
    try {
      outbox
          .schedule(io.vanillabp.integration.spi.PhaseTwoCall
              .of(
                  io.vanillabp.integration.spi.PhaseOperation.CORRELATE_MESSAGE,
                  "test-module",
                  "Test",
                  "4712",
                  null,
                  java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "Paid")));
      assertEquals(
          java.util.Optional.of(true),
          outbox.hasPendingCallsOf("test-module", "Test", "4712"),
          "the entry written a moment ago in this transaction counts");
    } finally {
      userTransaction.rollback();
    }

    assertEquals(
        java.util.Optional.of(false),
        outbox.hasPendingCallsOf("test-module", "Test", "4712"),
        "the rolled back entry is gone");

  }

  /**
   * Writes an entry which stays where it is. Its next attempt lies an hour ahead, so the
   * dispatcher's poll does not pick it up.
   *
   * @param id The id of the entry
   * @param writtenAt When it was written
   */
  private void writeWaitingEntry(
      final String id,
      final java.time.Instant writtenAt) {

    outbox()
        .writeWaitingEntry(
            id,
            "test-module",
            "Test",
            "START_WORKFLOW",
            "4711",
            "test",
            writtenAt,
            java.time.Instant.now().plus(java.time.Duration.ofHours(1)));

  }

  @Test
  @DisplayName("The entry is written best-effort, phase two dispatched after commit and the entry marked DONE")
  public void entryWrittenAndPhaseTwoDispatchedAfterCommit() throws Exception {

    userTransaction.begin();
    final Aggregate attachedAggregate;
    try {
      attachedAggregate = workflowService.startWorkflow("commit-test");
      // best-effort mode: the entry is written at once, so it is visible BEFORE the
      // commit (no MongoDB transaction, a documented window)
      assertEquals(1, outbox().entriesAtAll());
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();

    assertNotNull(attachedAggregate.getId());

    // after the commit, phase two has to be dispatched with the aggregate's ID. The ID is
    // converted back from its string form to the original type
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // marked DONE, not deleted: the entry stays visible until the retention cleanup
    final var deadline = System.currentTimeMillis() + 10000;
    while (outbox().entriesDispatched() == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "outbox entry was not marked DONE");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("On rollback the already-written entry is deleted best-effort and phase two never dispatched")
  public void rollbackDeletesEntryAndNoPhaseTwo() throws Exception {

    userTransaction.begin();
    workflowService.startWorkflow("rollback-test");
    // best-effort mode: the entry is already visible inside the transaction
    assertEquals(1, outbox().entriesAtAll());
    userTransaction.rollback();

    // the rollback handling deleted the entry best-effort
    assertEquals(0, outbox().entriesAtAll(), "the entry has to be deleted on rollback");

    // wait longer than the poll interval. Phase two must never be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(listener.getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A duplicate schedule while the first one is still pending is a no-op")
  public void duplicateScheduleAgainstAPendingEntryIsNoOp() throws Exception {

    // both starts are in ONE transaction, so nothing is dispatched in between. The second
    // start meets an entry which is still waiting, and the unique index over the dedup key
    // makes it a no-op. This test WANTS to plan against a pending entry, so it must not wait
    // for the window to close
    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("dedup-pending");
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    listener.awaitInvocations(1, 10000);
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(1, listener.getInvocations().size(), "only one of the two starts was planned");
    assertEquals(1, outbox().entriesAtAll());

  }

  @Test
  @DisplayName("A repetition after the dispatch is a new operation - the key does not block it")
  public void aRepetitionAfterTheDispatchIsPlanned() throws Exception {

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("dedup-dispatched");
    userTransaction.commit();

    listener.awaitInvocations(1, 10000);
    awaitDeduplicationWindowClosed();

    // the dispatched entry took its own id as dedup key. So the same operation can be
    // planned again (see decision 22 in the repository's DECISIONS.md)
    userTransaction.begin();
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    listener.awaitInvocations(2, 10000);
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(2, listener.getInvocations().size(), "the repetition reached the BPMS");
    assertEquals(2, outbox().entriesAtAll());

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

  @Test
  @DisplayName("A left-over entry (like after a crash) is dispatched by the recovery poller")
  public void leftOverEntryIsDispatchedByPoller() throws Exception {

    // pretend that a crashed instance committed this entry. The outbox of this JVM never
    // saw it being scheduled, so only the poller can pick it up
    final var now = java.time.Instant.now();
    outbox()
        .writeWaitingEntry(
            UUID.randomUUID().toString(),
            "test-module",
            "WorkflowService",
            "START_WORKFLOW",
            "4711",
            "test",
            "START_WORKFLOW|test-module|WorkflowService|4711",
            now,
            now);

    final var invocations = listener.awaitInvocations(1, 10000);
    // the aggregate ID was converted back to the ID type of the aggregate (Long)
    assertEquals(4711L, invocations.getFirst());

  }

}
