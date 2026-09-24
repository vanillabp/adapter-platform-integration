package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * What happens when the dispatch got through and the write which says so did not.
 * <p>
 * That write runs after the renewal of the lease was let go, so it stands outside the attempt
 * and outside the lane's own error handling. An exception there used to leave the thread of
 * the lane without a word, and the entry lay claimed until its lease ran out: the operation
 * had reached the BPMS and nothing said what had become of it. It is reported and planned
 * again instead, the way any attempt which did not get through is, and the entry being planned
 * again is what this test reads.
 * <p>
 * The failing write is a real one, not a double: the unique index over <code>dedupKey</code>
 * is what a dispatched entry writes its own id into, so an entry whose id another document
 * already holds there cannot be marked. That is the same error a store hands back when two
 * nodes race, and it needs nothing mocked.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoAMarkWhichDidNotGetThroughIsPlannedAgainTest {

  private static final String DATABASE = "outbox-failed-mark-it";

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String MODULE = "test-module";

  private static final String PROCESS = "WorkflowService";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      // long enough that exactly one attempt happens while this test looks
      .overrideConfigKey("vanillabp.outbox.attempt-frequency", "PT30S");

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  MongoClient mongoClient;

  private MongoCollection<Document> outbox() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection("vanillabp-phase-two-outbox");

  }

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    outbox().deleteMany(new Document());

  }

  /**
   * Writes the entry to be dispatched, and next to it a dispatched entry which already holds
   * that entry's id as its <code>dedupKey</code>. The mark of the first one therefore cannot
   * be written.
   *
   * @return The id of the entry to be dispatched
   */
  private String anEntryWhoseMarkCannotBeWritten() {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    outbox()
        .insertOne(new Document()
            .append("_id", UUID.randomUUID().toString())
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", "START_WORKFLOW")
            .append("aggregateId", "another-aggregate")
            .append("adapterId", "test")
            .append("dedupKey", id)
            .append("status", "DONE")
            .append("createdAt", now)
            .append("doneAt", now)
            .append("attempts", 1));
    outbox()
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", "START_WORKFLOW")
            .append("aggregateId", "failed-mark-aggregate")
            .append("adapterId", "test")
            .append("idempotencyKey", "the-key-of-"
                + id)
            .append("dedupKey", "the-key-of-"
                + id)
            .append("status", "OPEN")
            .append("createdAt", now)
            .append("attempts", 0)
            .append("nextAttemptAt", now)
            .append("leasedBy", null)
            .append("leasedUntil", null));
    return id;

  }

  private Document entryOf(
      final String id) {

    final var entry = outbox()
        .find(Filters.eq("_id", id))
        .first();
    assertNotNull(entry, "the entry is gone");
    return entry;

  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final java.util.concurrent.Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED;
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("An entry whose mark did not get through is planned again")
  public void aMarkWhichDidNotGetThroughIsPlannedAgain() throws Exception {

    final var entry = anEntryWhoseMarkCannotBeWritten();

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    waitUntil(
        "the entry whose mark could not be written was never planned again",
        () -> entryOf(entry).getInteger("attempts") == 1);

    final var afterTheAttempt = entryOf(entry);
    assertEquals(
        "OPEN",
        afterTheAttempt.getString("status"),
        "an entry whose mark did not get through is still waiting for one");
    assertTrue(
        afterTheAttempt.getDate("nextAttemptAt").toInstant().isAfter(Instant.now()),
        "the entry was not planned for a later moment");

  }

}
