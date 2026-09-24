package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.outbox.mongo.PhaseTwoOutboxEntry;
import io.vanillabp.integration.outbox.mongo.PhaseTwoPayloadDocument;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

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
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoBlockedEntryKeepsItsPayloadTest.MongoHousekeepingTestConfiguration.class
})
@DirtiesContext
@Testcontainers
public class MongoBlockedEntryKeepsItsPayloadTest {

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  private static final String PAYLOAD_COLLECTION = OUTBOX_COLLECTION
      + "-payloads";

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

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class MongoHousekeepingTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {

      return builder -> builder.applyConnectionString(new ConnectionString(mongoDb.getReplicaSetUrl()));

    }

  }

  @Autowired
  private MongoTemplate mongoTemplate;

  /**
   * Writes a payload the way a call which carried one wrote it, old enough to be
   * removed by age.
   *
   * @param reference The reference an entry names it by
   * @param content What the call carried
   */
  private void payloadOlderThanTheRetention(
      final String reference,
      final String content) {

    mongoTemplate
        .insert(
            new PhaseTwoPayloadDocument(
                reference, "test-module", "TestProcess", "sample:NOTIFY", content
                    .getBytes(StandardCharsets.UTF_8), LONG_BEFORE_THE_RETENTION),
            PAYLOAD_COLLECTION);

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
      final Instant doneAt) {

    final var id = UUID.randomUUID().toString();
    mongoTemplate
        .insert(
            new PhaseTwoOutboxEntry(
                id, "test-module", "TestProcess", "sample:NOTIFY", "4711", "test", Map
                    .of(PhaseTwoCall.ARG_PAYLOAD_REFERENCE,
                        payloadReference), null, id, status, LONG_BEFORE_THE_RETENTION, 0, LONG_BEFORE_THE_RETENTION, doneAt, null, null),
            OUTBOX_COLLECTION);
    return id;

  }

  private PhaseTwoPayloadDocument payload(
      final String reference) {

    return mongoTemplate
        .findOne(Query.query(Criteria.where("_id").is(reference)), PhaseTwoPayloadDocument.class, PAYLOAD_COLLECTION);

  }

  private Object entryOf(
      final String id) {

    return mongoTemplate
        .findOne(Query.query(Criteria.where("_id").is(id)), PhaseTwoOutboxEntry.class, OUTBOX_COLLECTION);

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
      final Supplier<Object> document,
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
    final var blockedEntry = entry(blockedPayload, PhaseTwoOutboxEntry.STATUS_BLOCKED, null);
    final var dispatchedEntry = entry(
        dispatchedPayload, PhaseTwoOutboxEntry.STATUS_DONE, LONG_BEFORE_THE_RETENTION);
    payloadOlderThanTheRetention(blockedPayload, KEPT_FOR_THE_OPERATOR);
    payloadOlderThanTheRetention(dispatchedPayload, "the state a dispatch already carried");
    payloadOlderThanTheRetention(orphanPayload, "written by a write which was rolled back");

    awaitRemoved(() -> entryOf(dispatchedEntry), "a dispatched entry goes when its retention ran out");
    awaitRemoved(() -> payload(dispatchedPayload), "the entry took its payload with it");
    awaitRemoved(() -> payload(orphanPayload), "a payload no entry names is removed by age");

    assertNotNull(entryOf(blockedEntry), "a blocked entry waits for a person and no retention removes it");
    assertArrayEquals(
        KEPT_FOR_THE_OPERATOR.getBytes(StandardCharsets.UTF_8),
        payload(blockedPayload).getPayload(),
        "the entry is still there, so its payload has to be there as well");

  }

}
