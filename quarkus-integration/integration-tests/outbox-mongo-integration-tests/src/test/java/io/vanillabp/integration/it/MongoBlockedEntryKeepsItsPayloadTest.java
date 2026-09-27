package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
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

  /**
   * Older than the default retention of seven days, so everything written here is old
   * enough to be removed - which makes the entries the only reason a payload stays.
   */
  private static final Instant LONG_BEFORE_THE_RETENTION = Instant.now().minus(Duration.ofDays(10));

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

  /**
   * The store of the application, asked through the reader so that neither a collection
   * nor a field is named here.
   */
  private MongoPhaseTwoOutboxReader store() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase(DATABASE));

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

    store()
        .writePayload(
            reference,
            "test-module",
            "TestProcess",
            "sample:NOTIFY",
            content.getBytes(StandardCharsets.UTF_8),
            LONG_BEFORE_THE_RETENTION);

  }

  /**
   * Writes an entry the store put aside for a person, the way it stood before this test
   * began.
   *
   * @param payloadReference The payload this entry names
   * @return The entry's id
   */
  private String blockedEntry(
      final String payloadReference) {

    final var id = UUID.randomUUID().toString();
    store()
        .writeBlockedEntry(
            id,
            "test-module",
            "TestProcess",
            "sample:NOTIFY",
            "4711",
            "test",
            Map.of(PhaseTwoCall.ARG_PAYLOAD_REFERENCE, payloadReference),
            LONG_BEFORE_THE_RETENTION);
    return id;

  }

  /**
   * Writes an entry which was dispatched long ago and whose retention ran out.
   *
   * @param payloadReference The payload this entry names
   * @return The entry's id
   */
  private String dispatchedEntry(
      final String payloadReference) {

    final var id = UUID.randomUUID().toString();
    store()
        .writeDispatchedEntry(
            id,
            "test-module",
            "TestProcess",
            "sample:NOTIFY",
            "4711",
            "test",
            id,
            Map.of(PhaseTwoCall.ARG_PAYLOAD_REFERENCE, payloadReference),
            LONG_BEFORE_THE_RETENTION);
    return id;

  }

  private MongoPhaseTwoOutboxReader.Payload payload(
      final String reference) {

    return store()
        .payloadOf(reference)
        .orElse(null);

  }

  private MongoPhaseTwoOutboxReader.Entry entryOf(
      final String id) {

    return store()
        .entryById(id)
        .orElse(null);

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
      final Supplier<?> document,
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
    final var blockedEntry = blockedEntry(blockedPayload);
    final var dispatchedEntry = dispatchedEntry(dispatchedPayload);
    payloadOlderThanTheRetention(blockedPayload, KEPT_FOR_THE_OPERATOR);
    payloadOlderThanTheRetention(dispatchedPayload, "the state a dispatch already carried");
    payloadOlderThanTheRetention(orphanPayload, "written by a write which was rolled back");

    awaitRemoved(() -> entryOf(dispatchedEntry), "a dispatched entry goes when its retention ran out");
    awaitRemoved(() -> payload(dispatchedPayload), "the entry took its payload with it");
    awaitRemoved(() -> payload(orphanPayload), "a payload no entry names is removed by age");

    assertNotNull(entryOf(blockedEntry), "a blocked entry waits for a person and no retention removes it");
    assertArrayEquals(
        KEPT_FOR_THE_OPERATOR.getBytes(StandardCharsets.UTF_8),
        payload(blockedPayload).payload(),
        "the entry is still there, so its payload has to be there as well");
    assertEquals(1, store().payloadsAtAll(), "only the payload of the blocked entry is left");

  }

}
