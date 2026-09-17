package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.CountingCommandListener;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * What a quiet application costs on the MongoDB store of Quarkus, counted in commands rather
 * than measured in seconds. A poll was a claim attempt plus a retention delete whether or not
 * anything was waiting, and an application sitting in a timer paid for them every ten
 * seconds.
 * <p>
 * The cap is an hour here, so a command against the outbox collection while nothing is due
 * would have to come from a poller which ignored what its store told it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOutboxSleepsWhileNothingIsDueTest {

  private static final String DATABASE = "outbox-sleeping-it";

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(CountingCommandListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideRuntimeConfigKey("vanillabp.outbox.poll-interval", "PT1H");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  private MongoCollection<Document> outbox() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(OUTBOX_COLLECTION);

  }

  /**
   * How long the collection has to stay untouched before the dispatch counts as over.
   * Below the three seconds the silence is measured over, so a poller asking on a rhythm
   * shorter than that is caught by the wait rather than passing through it.
   */
  private static final long QUIET_FOR_MS = 500;

  private void awaitNothingLeftUndone() throws Exception {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (outbox().countDocuments(new Document("status", "OPEN")) > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never dispatched");
      Thread.sleep(50);
    }

  }

  /**
   * Waits until the dispatch which emptied the store has stopped talking to the
   * collection.
   * <p>
   * Nothing left OPEN is not the end of that dispatch. The poll which marked the entry
   * looks for the next due one, deletes what the retention lets go and reads when to
   * wake up again, and those commands arrive AFTER the wait above has returned.
   * Forgetting what was sent in between is what turned this test red once in a while on
   * a run where nothing was wrong.
   * <p>
   * The wait reads the commands the listener collected and asks the collection nothing
   * itself, because a question of its own would be the traffic it is waiting out. A
   * store which never goes quiet is the very thing this test is about, so the deadline
   * says that instead of timing out without a word.
   */
  private void awaitTheDispatchWentQuiet() throws Exception {

    final var deadline = System.currentTimeMillis() + 30_000;
    var commandsSeen = CountingCommandListener.commandsOn(OUTBOX_COLLECTION).size();
    var quietSince = System.currentTimeMillis();
    while ((System.currentTimeMillis() - quietSince) < QUIET_FOR_MS) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the outbox collection was asked something over and over while nothing was due");
      Thread.sleep(20);
      final var commandsNow = CountingCommandListener.commandsOn(OUTBOX_COLLECTION).size();
      if (commandsNow != commandsSeen) {
        commandsSeen = commandsNow;
        quietSince = System.currentTimeMillis();
      }
    }

  }

  @Test
  @DisplayName("The questions the poller asks are answered from an index")
  public void theQuestionsOfThePollerAreIndexed() {

    // without these each question reads the whole collection, and that cost grows with everything
    // the collection ever held while the wake-ups stay as rare
    final var keys = new java.util.ArrayList<org.bson.Document>();
    outbox().listIndexes().forEach(index -> keys.add(index.get("key", org.bson.Document.class)));

    assertTrue(
        keys.contains(new org.bson.Document("status", 1).append("nextAttemptAt", 1)),
        "the due question and the claim which picks an entry up read these two: "
            + keys);
    assertTrue(
        keys.contains(new org.bson.Document("status", 1).append("doneAt", 1)),
        "the retention question and its delete read these two: "
            + keys);

  }

  @Test
  @DisplayName("Nothing is asked of the collection while the store owes nothing")
  public void aQuietStoreIsAskedNothing() throws Exception {

    listener.reset();
    userTransaction.begin();
    workflowService.startWorkflow("quiet-store");
    userTransaction.commit();
    awaitNothingLeftUndone();
    // the silence below says nothing unless this listener can see traffic at all
    assertFalse(
        CountingCommandListener.commandsOn(OUTBOX_COLLECTION).isEmpty(),
        "dispatching an entry has to show up here, or this test measures its own instrument");

    awaitTheDispatchWentQuiet();
    CountingCommandListener.forgetWhatWasSent();
    Thread.sleep(3000);

    assertEquals(
        List.of(),
        CountingCommandListener.commandsOn(OUTBOX_COLLECTION),
        "a store with nothing to do has to be left alone");

  }

}
