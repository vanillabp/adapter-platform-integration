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
 * The renewal notices it - the write matches no document - and the dispatch which lost the
 * entry runs to its end. What it must NOT do is write down how that ended, because the node
 * holding the entry now is doing the same operation and its answer is the one the collection
 * keeps. A late "blocked" over a finished entry would be the worst of them: the operation
 * reached the BPMS and somebody would be asked to repair it by hand.
 * <p>
 * The node which loses the entry is the real dispatcher of this application, stopped inside
 * the adapter. The node which takes the entry over is played by the test, with the two writes
 * a dispatch of another node leaves behind: a Quarkus application holds one dispatcher, so a
 * second real one would need a second application. That costs nothing here, because the write
 * this test is about is the one which has to be REFUSED, and that write is the real
 * dispatcher's.
 * <p>
 * A second entry says when that refused write is over. The dispatcher polls in a loop, so it
 * reaches the next due entry only after it wrote down how the attempt before ended. The test
 * therefore waits for the second operation to reach the adapter instead of waiting a while and
 * calling the silence a result.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoADispatchWhichLostItsLeaseTest {

  private static final String DATABASE = "outbox-lost-lease-it";

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String MODULE = "test-module";

  private static final String PROCESS = "WorkflowService";

  /**
   * Who holds the entry once the test took it over. It only has to be a name this node never
   * writes, and one an operator recognises in the collection.
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
   * The outbox of the application, asked through the reader so that neither a collection
   * nor a field is named here.
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
   * Writes an entry the way a node which crashed left it behind: nobody holds it and it is
   * due, so only a poll can pick it up.
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
   * Does to the entry what another node's dispatch does to it: it claims the entry, which is
   * what takes the lease away from the node still working on it, and then writes down that its
   * own attempt got through.
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
   * Runs the story both tests share: the dispatcher of this application takes the entry and is
   * stopped inside the adapter, another node takes it over and dispatches it, and then the
   * held dispatch is allowed to end.
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
      // what says that the held dispatch is over and wrote whatever it had to write. It is
      // written down while the dispatcher still holds this entry, so the next entry it takes
      // is the one after
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

    // the failure which blocks an entry with one attempt - the write which would be the most
    // expensive of all to land on an entry the other node has just finished
    final var afterTheHeldDispatch = theEntryChangesHands(
        "lost-entry-blocked-aggregate", new DummyPermanentFailure(
            "the adapter says repeating cannot help"));

    assertTrue(
        afterTheHeldDispatch.wasDispatched(),
        "the entry the other node dispatched was blocked by the one which lost it");

  }

}
