package io.vanillabp.integration.test.outbox;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;

/**
 * An entry whose BPMS does not report its workflow yet waits for time, not for attempts, on
 * the MongoDB store of Spring Boot as on every other store.
 * <p>
 * Each answer "not yet" used to count an attempt. Against a Camunda 8 cluster whose exporter
 * was paused that blocked an entry after fifty windows of ten seconds, while a database which
 * was away just as long blocked nothing. The application here allows two attempts, so a store
 * which still counted the answers would block the entry long before the read model catches
 * up.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = {
        TestApplication.class, MongoWaitingForAReadModelUsesNoAttemptsTest.MongoReadModelTestConfiguration.class
    },
    properties = {
        "vanillabp.outbox.block-after-attempts=2", "vanillabp.outbox.wait-for-visibility-at-most=PT1H"
    })
@DirtiesContext
@Testcontainers
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

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class MongoReadModelTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {

      return builder -> builder.applyConnectionString(new ConnectionString(mongoDb.getReplicaSetUrl()));

    }

  }

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    store().removeAllEntries();

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
    store()
        .writeWaitingEntry(
            id,
            "test-module",
            "SampleWorkflowService",
            "START_WORKFLOW",
            aggregateId,
            "test",
            "START_WORKFLOW|test-module|SampleWorkflowService|%s".formatted(aggregateId),
            writtenAt,
            Instant.now());
    return id;

  }

  private MongoPhaseTwoOutboxReader store() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoTemplate.getDb());

  }

  private MongoPhaseTwoOutboxReader.Entry entryOf(
      final String id) {

    return store()
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
    final var entry = anEntryWrittenAt("lagging-read-model", Instant.now());

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
    final var entry = anEntryWrittenAt("read-model-never-caught-up", Instant.now().minus(Duration.ofHours(2)));

    waitUntil("the entry which waited too long was not blocked", () -> entryOf(entry).isBlocked());
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    assertEquals(
        1,
        listener.getInvocations().size(),
        "the first answer 'not yet' after the time ran out blocks the entry, and nothing asks again");

  }

}
