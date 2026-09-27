package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
 * What the lease of the MongoDB store is for: an entry somebody is dispatching stays that
 * node's entry however long the dispatch takes, and the attempts count what was attempted.
 * <p>
 * This store dispatches on lanes of its own, so its poller keeps asking while a dispatch is
 * under way. What makes a poll leave the entry alone is the live lease, this node's next poll
 * as much as another node's. The test plays another node: while a dispatch is under way it asks
 * the collection the question a poll asks, and the answer has to stay "nothing to take" for the
 * whole dispatch. Before the lease was renewed the answer flipped after one
 * <code>vanillabp.outbox.attempt-frequency</code>, half a second here, and the operation was
 * carried out twice.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoDispatchOutlastingItsLeaseTest {

  /**
   * How long one dispatch stays inside the adapter - six times the lease this application
   * configures, so every poll in that window used to take the entry.
   */
  private static final long A_DISPATCH_WHICH_TAKES_ITS_TIME = 3000;

  /**
   * How long a test waits before it says that nothing more happened. Three of the windows a
   * second taker used to appear in. It is a guard and not a measurement of speed: a machine
   * which leaves this JVM without a turn only makes the wait longer, and what is asserted
   * afterwards is a count which did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  private static final long UNTIL_IT_HAPPENED = 30_000;

  /**
   * How long the test asks its question while the dispatch is under way. Half the dispatch,
   * which is three leases and therefore three windows a second taker used to appear in, and it
   * ends well before the dispatch does - a question asked at the very end would race with the
   * mark the dispatch writes and read the attempt it counted there.
   */
  private static final long WHILE_THE_DISPATCH_IS_CLEARLY_RUNNING = A_DISPATCH_WHICH_TAKES_ITS_TIME / 2;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "outbox-lease-it");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  /**
   * The outbox of the application, asked through the reader so that neither a collection
   * nor a field is named here.
   */
  private MongoPhaseTwoOutboxReader outbox() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase("outbox-lease-it"));

  }

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    outbox().removeAllEntries();

  }

  /**
   * Puts the delay back, so a class running after this one is not dispatching slowly for a
   * reason nobody can see in it.
   */
  @AfterEach
  public void letDispatchesBeFastAgain() {

    listener.reset();

  }

  /**
   * Writes an entry the way a node which crashed left it behind: this JVM's outbox never saw
   * it being scheduled, so only a poll can pick it up.
   *
   * @param aggregateId The aggregate the operation belongs to
   * @param leasedBy Who holds the entry, <code>null</code> for an entry nobody holds
   * @param leasedUntil How long that hold lasts, <code>null</code> with the above
   * @return The entry's id
   */
  private String anEntryDueNow(
      final String aggregateId,
      final String leasedBy,
      final Instant leasedUntil) {

    final var id = UUID.randomUUID().toString();
    final var now = Instant.now();
    outbox()
        .writeWaitingEntry(
            id,
            "test-module",
            "WorkflowService",
            "START_WORKFLOW",
            aggregateId,
            "test",
            "START_WORKFLOW|test-module|WorkflowService|%s".formatted(aggregateId),
            now,
            now);
    if (leasedBy != null) {
      outbox().leaseEntry(id, leasedBy, leasedUntil);
    }
    return id;

  }

  private MongoPhaseTwoOutboxReader.Entry entryOf(
      final String id) {

    return outbox()
        .entryById(id)
        .orElseThrow();

  }

  /**
   * The question a poll of ANOTHER instance asks: is there an entry which is due and which
   * nobody holds? This is a read and not the claim itself, so asking it changes nothing for
   * the dispatcher under test.
   *
   * @return Whether another node would take an entry right now
   */
  private boolean anotherNodeWouldTakeSomething() {

    return outbox().somethingIsDueAndUnleased();

  }

  private void waitUntil(
      final String whatDidNotHappen,
      final java.util.concurrent.Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED;
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("No other node can take an entry while it is being dispatched, however long that takes")
  public void aSlowDispatchStaysWithTheNodeCarryingIt() throws Exception {

    listener.eachDispatchTakes(A_DISPATCH_WHICH_TAKES_ITS_TIME);
    final var entry = anEntryDueNow("4711", null, null);

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    // the adapter is inside the dispatch now. Ask over the next three leases, and the entry
    // has to stay out of reach for every single question
    final var whileItIsRunning = System.currentTimeMillis() + WHILE_THE_DISPATCH_IS_CLEARLY_RUNNING;
    while (System.currentTimeMillis() < whileItIsRunning) {
      assertFalse(
          anotherNodeWouldTakeSomething(),
          "another node would have taken the entry while it was being dispatched");
      assertEquals(
          0,
          entryOf(entry).attempts(),
          "the claim counted an attempt although no attempt has ended");
      Thread.sleep(100);
    }

    waitUntil("the dispatch never finished", () -> entryOf(entry).wasDispatched());
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    assertEquals(1, listener.getInvocations().size(), "the operation reached the adapter more than once");
    assertEquals(
        1,
        entryOf(entry).attempts(),
        "one attempt was made, so one attempt is what the entry counts");

  }

  @Test
  @DisplayName("An entry dispatched right away counts its one attempt too")
  public void attemptsCountWhatWasAttempted() throws Exception {

    userTransaction.begin();
    workflowService.startWorkflow("one attempt");
    userTransaction.commit();

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    waitUntil("the entry was never marked", () -> outbox().entriesDispatched() == 1);
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    final var dispatched = outbox()
        .entriesDispatchedAlready()
        .getFirst();
    assertNotNull(dispatched);
    assertEquals(1, dispatched.attempts(), "an attempt which was made is an attempt which counts");

  }

  @Test
  @DisplayName("An entry whose holder died is taken over, one whose lease still runs is left alone")
  public void anEntryWhoseHolderDiedIsTakenOver() throws Exception {

    // both are due, and the lease is the only thing telling them apart
    final var leftBehind = anEntryDueNow("4711", "a-node-which-died", Instant.now().minus(Duration.ofMinutes(1)));
    final var heldBySomebodyElse = anEntryDueNow(
        "4712", "a-node-which-is-working", Instant.now().plus(Duration.ofHours(1)));

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    waitUntil("the entry of the node which died was never taken over", () -> entryOf(leftBehind).wasDispatched());
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    // the aggregate ID was converted back to the aggregate's ID type (Long)
    assertEquals(
        List.of(4711L),
        listener.getInvocations(),
        "an entry whose lease is still running was dispatched as well");
    final var untouched = entryOf(heldBySomebodyElse);
    assertTrue(untouched.isWaiting(), "the leased entry was dispatched");
    assertEquals(0, untouched.attempts(), "the leased entry was attempted");

  }

}
