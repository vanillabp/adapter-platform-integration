package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import jakarta.inject.Inject;

/**
 * An entry whose BPMS does not report its workflow yet waits for time, not for attempts, on
 * the MongoDB store of Quarkus as on every other store.
 * <p>
 * Each answer "not yet" used to count an attempt. Against a Camunda 8 cluster whose exporter
 * was paused that blocked an entry after fifty windows of ten seconds, while a database which
 * was away just as long blocked nothing. The application here allows two attempts, so a store
 * which still counted the answers would block the entry long before the read model catches
 * up.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoWaitingForAReadModelUsesNoAttemptsTest {

  /**
   * The window the adapter names. Short, so many answers fit into a test.
   */
  private static final Duration WINDOW = Duration.ofMillis(100);

  /**
   * How many times the adapter says "not yet" before the workflow shows up. More than the two
   * attempts this application allows.
   */
  private static final int ANSWERS_BEFORE_THE_WORKFLOW_SHOWS_UP = 4;

  /**
   * How long a test waits before it says that nothing more happened. Several windows, so an
   * entry which was wrongly given back would have come again.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String DATABASE = "outbox-read-model-it";

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
      .overrideConfigKey("vanillabp.outbox.block-after-attempts", "2")
      .overrideConfigKey("vanillabp.outbox.wait-for-visibility-at-most", "PT1H");

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  MongoClient mongoClient;

  private MongoPhaseTwoOutboxReader outbox() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase(DATABASE));

  }

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    outbox().removeAllEntries();

  }

  /**
   * Puts the answers back, so a class running after this one is not told "not yet" for a
   * reason nobody can see in it.
   */
  @AfterEach
  public void answerNormallyAgain() {

    listener.reset();

  }

  /**
   * Writes an entry which is due now and claims to have been written at the given moment.
   * This JVM's outbox never saw it being scheduled, so a poll picks it up.
   *
   * @param aggregateId The aggregate the operation belongs to
   * @param writtenAt When the entry was written
   * @return The entry's id
   */
  private String anEntryWrittenAt(
      final String aggregateId,
      final Instant writtenAt) {

    final var id = UUID.randomUUID().toString();
    outbox()
        .writeWaitingEntry(
            id,
            "test-module",
            "WorkflowService",
            "START_WORKFLOW",
            aggregateId,
            "test",
            "START_WORKFLOW|test-module|WorkflowService|%s".formatted(aggregateId),
            writtenAt,
            Instant.now());
    return id;

  }

  private MongoPhaseTwoOutboxReader.Entry entryOf(
      final String id) {

    return outbox()
        .entryById(id)
        .orElseThrow();

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
  @DisplayName("Answers of a read model which is behind use no attempts, however many there are")
  public void answersOfALaggingReadModelUseNoAttempts() throws Exception {

    listener.answerNotYet(ANSWERS_BEFORE_THE_WORKFLOW_SHOWS_UP, WINDOW);
    final var entry = anEntryWrittenAt("4712", Instant.now());

    waitUntil("the entry was not dispatched after the read model caught up", () -> entryOf(entry).wasDispatched());

    assertEquals(
        ANSWERS_BEFORE_THE_WORKFLOW_SHOWS_UP + 1,
        listener.getInvocations().size(),
        "the entry came back after every window and went through once the read model caught up");
    assertEquals(
        1,
        entryOf(entry).attempts(),
        "only the attempt which went through is counted, the answers 'not yet' are not");

  }

  @Test
  @DisplayName("An entry which waited longer than wait-for-visibility-at-most is blocked")
  public void anEntryWhichWaitedTooLongIsBlocked() throws Exception {

    listener.answerNotYet(Integer.MAX_VALUE, WINDOW);
    // written two hours ago and never dispatched, which is what an exporter which stopped
    // for two hours leaves behind
    final var entry = anEntryWrittenAt("4713", Instant.now().minus(Duration.ofHours(2)));

    waitUntil("the entry which waited too long was not blocked", () -> entryOf(entry).isBlocked());
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    assertEquals(
        1,
        listener.getInvocations().size(),
        "the first answer 'not yet' after the time ran out blocks the entry, and nothing asks again");

  }

}
