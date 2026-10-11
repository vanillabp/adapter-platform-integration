package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyPermanentFailure;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import jakarta.inject.Inject;

/**
 * What happens to a dispatch of the MongoDB store whose entry was taken over while it ran.
 * <p>
 * The renewal of the lease notices it, because its write matches no document. The dispatch
 * which lost the entry runs to its end. But it must NOT write down how that ended. The node
 * which holds the entry now does the same operation, and the collection keeps that node's
 * answer. A late "blocked" over a finished entry would be the worst case: the operation
 * reached the BPMS, and somebody would be asked to repair it by hand.
 * <p>
 * The node which loses the entry is the real dispatcher of this application, stopped inside
 * the adapter. The test plays the node which takes the entry over. It does the two writes
 * which a dispatch of another node leaves behind. A Quarkus application holds one
 * dispatcher, so a second real one would need a second application. That costs nothing here.
 * This test is about the write which has to be REFUSED, and that is the real dispatcher's
 * write.
 * <p>
 * A second entry tells when that refused write is over. The dispatcher polls in a loop. So
 * it reaches the next due entry only after it wrote down how the attempt before ended. That
 * is why the test waits for the second operation to reach the adapter. It does not just wait
 * a while and call the silence a result.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoADispatchWhichLostItsLeaseTest {

  private static final String DATABASE = "outbox-lost-lease-it";

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String MODULE = "test-module";

  private static final String PROCESS = "WorkflowService";

  /**
   * Who holds the entry once the test took it over. It only has to be a name which this node
   * never writes, and which an operator recognises in the collection.
   */
  private static final String THE_NODE_TAKING_THE_ENTRY = "a-node-which-took-the-entry-over";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE);

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  MongoClient mongoClient;

  /**
   * The outbox of the application. It is asked through the reader, so this test names
   * neither a collection nor a field.
   */
  private MongoPhaseTwoOutboxReader outbox() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase(DATABASE));

  }

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    outbox().removeAllEntries();

  }

  @AfterEach
  public void letGoOfWhatIsStillHeld() {

    listener.reset();

  }

  /**
   * Writes an entry the way a crashed node left it behind. Nobody holds it and it is due, so
   * only a poll can pick it up.
   *
   * @param aggregateId The aggregate the operation belongs to
   * @return The entry's id
   */
  private String anEntryDueNow(
      final String aggregateId) {

    final var id = UUID.randomUUID().toString();
    final var now = Instant.now();
    outbox()
        .writeWaitingEntry(
            id,
            MODULE,
            PROCESS,
            "START_WORKFLOW",
            aggregateId,
            "test",
            "START_WORKFLOW|%s|%s|%s".formatted(MODULE, PROCESS, aggregateId),
            now,
            now);
    return id;

  }

  /**
   * Does to the entry what a dispatch of another node does to it. It claims the entry, and
   * that takes the lease away from the node which still works on it. Then it writes down that
   * its own attempt got through.
   *
   * @param id The entry which changes hands
   */
  private void anotherNodeTakesTheEntryAndDispatchesIt(
      final String id) {

    outbox().anotherNodeTakesTheEntryAndDispatchesIt(id, THE_NODE_TAKING_THE_ENTRY);

  }

  private MongoPhaseTwoOutboxReader.Entry entryOf(
      final String id) {

    return outbox()
        .entryById(id)
        .orElseThrow(() -> new AssertionError("the entry is gone"));

  }

  /**
   * Runs the story which both tests share. The dispatcher of this application takes the entry
   * and is stopped inside the adapter. Another node takes the entry over and dispatches it.
   * Then the held dispatch may end.
   *
   * @param aggregateId The aggregate of this test
   * @param theHeldDispatchEndsWith What the held dispatch throws, or <code>null</code> where
   *          it succeeds
   * @return The entry as the collection shows it after the held dispatch ended
   */
  private MongoPhaseTwoOutboxReader.Entry theEntryChangesHands(
      final String aggregateId,
      final RuntimeException theHeldDispatchEndsWith) throws Exception {

    final var hold = listener.holdTheNextDispatch();
    final var entry = anEntryDueNow(aggregateId);
    try {
      listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
      anotherNodeTakesTheEntryAndDispatchesIt(entry);
      final var afterTheOtherNode = entryOf(entry);
      // this entry tells when the held dispatch is over and wrote whatever it had to write.
      // It is written while the dispatcher still holds the first entry, so the next entry
      // the dispatcher takes is this one
      anEntryDueNow(aggregateId
          + "-the-entry-after");

      hold.endWith(theHeldDispatchEndsWith);
      listener.awaitInvocations(2, UNTIL_IT_HAPPENED);

      final var afterTheHeldDispatch = entryOf(entry);
      assertEquals(
          afterTheOtherNode,
          afterTheHeldDispatch,
          "the node which lost the entry wrote its result over the one of the node holding it");
      return afterTheHeldDispatch;
    } finally {
      hold.end();
    }

  }

  @Test
  @DisplayName("A dispatch which lost its entry does not write its success over the one which took it")
  public void aLostEntryKeepsWhatTheOtherNodeWrote() throws Exception {

    final var afterTheHeldDispatch = theEntryChangesHands("lost-entry-success-aggregate", null);

    assertEquals(
        1,
        afterTheHeldDispatch.attempts(),
        "one attempt ended on the entry of the node which held it, so one is what it counts");

  }

  @Test
  @DisplayName("A dispatch which lost its entry does not block an entry somebody else finished")
  public void aLostEntryIsNotBlockedByTheNodeWhichLostIt() throws Exception {

    // the failure which blocks an entry after one attempt. Of all writes, this one would cost
    // the most if it landed on an entry which the other node has just finished
    final var afterTheHeldDispatch = theEntryChangesHands(
        "lost-entry-blocked-aggregate", new DummyPermanentFailure(
            "the adapter says repeating cannot help"));

    assertTrue(
        afterTheHeldDispatch.wasDispatched(),
        "the entry the other node dispatched was blocked by the one which lost it");

  }

}
