package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;

import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * What the housekeeping of the MongoDB store removes and what it leaves: the retention
 * counts at the entry, so an entry which is blocked keeps its payload however long the
 * repair takes, a dispatched entry takes its payload with it when it goes, and a payload
 * no entry names is removed by age.
 * <p>
 * The documents are written here rather than scheduled, because what is under test is a
 * store which has been standing for longer than the retention. Nothing is dispatched
 * while the test runs: the entries are BLOCKED respectively DONE, and the poller passes
 * over both.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoBlockedEntryKeepsItsPayloadTest {

  private static final String DATABASE = "outbox-housekeeping-it";

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  private static final String PAYLOAD_COLLECTION = OUTBOX_COLLECTION
      + "-payloads";

  /**
   * Older than the default retention of seven days, so everything written here is old
   * enough to be removed - which makes the entries the only reason a payload stays.
   */
  private static final Date LONG_BEFORE_THE_RETENTION = Date.from(Instant.now().minus(Duration.ofDays(10)));

  /**
   * How long the test waits for the poll which cleans up. The application polls twice a
   * second, so this is a guard against a machine which leaves the JVM without a turn,
   * not a measurement of speed.
   */
  private static final long UNTIL_THE_HOUSEKEEPING_RAN = 30_000;

  private static final String KEPT_FOR_THE_OPERATOR = "the state an operator will send once the cause is gone";

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
      // the housekeeping runs in a window at night, and this test watches it work now.
      // A window which ends before it starts crosses midnight, so this one is open all
      // day but for the first minute of it
      .overrideConfigKey("vanillabp.outbox.housekeeping.start", "00:01")
      .overrideConfigKey("vanillabp.outbox.housekeeping.end", "00:00")
      .overrideConfigKey("vanillabp.outbox.housekeeping.zone", "Europe/Vienna");

  @Inject
  MongoClient mongoClient;

  private MongoCollection<Document> outbox() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(OUTBOX_COLLECTION);

  }

  private MongoCollection<Document> payloads() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(PAYLOAD_COLLECTION);

  }

  /**
   * Writes a payload the way a call which carried one wrote it, old enough to be removed
   * by age.
   *
   * @param reference The reference an entry names it by
   * @param content What the call carried
   */
  private void payloadOlderThanTheRetention(
      final String reference,
      final String content) {

    payloads()
        .insertOne(
            new Document()
                .append("_id", reference)
                .append("workflowModuleId", "test-module")
                .append("bpmnProcessId", "TestProcess")
                .append("operation", "sample:NOTIFY")
                .append("payload", new Binary(content.getBytes(StandardCharsets.UTF_8)))
                .append("createdAt", LONG_BEFORE_THE_RETENTION));

  }

  /**
   * Writes an entry the way the store left it behind before this test began.
   *
   * @param payloadReference The payload this entry names
   * @param status What became of the entry
   * @param doneAt When it was dispatched, <code>null</code> for an entry which was not
   * @return The entry's id
   */
  private String entry(
      final String payloadReference,
      final String status,
      final Date doneAt) {

    final var id = UUID.randomUUID().toString();
    outbox()
        .insertOne(
            new Document()
                .append("_id", id)
                .append("workflowModuleId", "test-module")
                .append("bpmnProcessId", "TestProcess")
                .append("operation", "sample:NOTIFY")
                .append("aggregateId", "4711")
                .append("adapterId", "test")
                .append("args", new Document(PhaseTwoCall.ARG_PAYLOAD_REFERENCE, payloadReference))
                .append("idempotencyKey", null)
                // the key of a blocked entry is released the way a dispatched one
                // releases it, which is why both carry their own id here
                .append("dedupKey", id)
                .append("status", status)
                .append("createdAt", LONG_BEFORE_THE_RETENTION)
                .append("attempts", 0)
                .append("nextAttemptAt", LONG_BEFORE_THE_RETENTION)
                .append("doneAt", doneAt));
    return id;

  }

  private Document payload(
      final String reference) {

    return payloads()
        .find(Filters.eq("_id", reference))
        .first();

  }

  private Document entryOf(
      final String id) {

    return outbox()
        .find(Filters.eq("_id", id))
        .first();

  }

  /**
   * Waits until the housekeeping removed one document, and says what was expected of it
   * where it never did.
   * <p>
   * Every document this test waits for is one the assertion is about. A sweep which ran
   * before the test had written everything down removes none of them, so the wait goes on
   * until the next sweep. Waiting for another document is what used to end this test early:
   * the orphaned payload was gone after a sweep which had not seen the dispatched entry
   * yet, and that entry was then still there.
   *
   * @param document Reads the document, <code>null</code> once it is gone
   * @param whatIsExpected What the housekeeping owes this document
   */
  private void awaitRemoved(
      final Supplier<Document> document,
      final String whatIsExpected) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + UNTIL_THE_HOUSEKEEPING_RAN;
    while (document.get() != null) {
      assertTrue(System.currentTimeMillis() < deadline, whatIsExpected);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A blocked entry keeps its payload, a dispatched entry takes its own with it, an orphan goes")
  public void theRetentionCountsAtTheEntry() throws Exception {

    final var blockedPayload = UUID.randomUUID().toString();
    final var dispatchedPayload = UUID.randomUUID().toString();
    final var orphanPayload = UUID.randomUUID().toString();
    // an entry is written before the payload it names, because a sweep between the two
    // writes would meet a payload nothing names yet and remove the very payload this
    // test says is kept
    final var blockedEntry = entry(blockedPayload, "BLOCKED", null);
    final var dispatchedEntry = entry(dispatchedPayload, "DONE", LONG_BEFORE_THE_RETENTION);
    payloadOlderThanTheRetention(blockedPayload, KEPT_FOR_THE_OPERATOR);
    payloadOlderThanTheRetention(dispatchedPayload, "the state a dispatch already carried");
    payloadOlderThanTheRetention(orphanPayload, "written by a write which was rolled back");

    awaitRemoved(() -> entryOf(dispatchedEntry), "a dispatched entry goes when its retention ran out");
    awaitRemoved(() -> payload(dispatchedPayload), "the entry took its payload with it");
    awaitRemoved(() -> payload(orphanPayload), "a payload no entry names is removed by age");

    assertNotNull(entryOf(blockedEntry), "a blocked entry waits for a person and no retention removes it");
    assertArrayEquals(
        KEPT_FOR_THE_OPERATOR.getBytes(StandardCharsets.UTF_8),
        payload(blockedPayload).get("payload", Binary.class).getData(),
        "the entry is still there, so its payload has to be there as well");
    assertEquals(1, payloads().countDocuments(), "only the payload of the blocked entry is left");

  }

}
