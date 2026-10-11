package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a quiet application costs on the MongoDB store of Spring Boot, counted in commands
 * and not measured in seconds. A poll used to be a claim attempt plus a retention delete,
 * whether or not anything was waiting. An application which waited in a timer paid for
 * both every ten seconds.
 * <p>
 * The cap is an hour here. So a command against the outbox collection while nothing is
 * due would have to come from a poller which ignored what its store told it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = {
        TestApplication.class, MongoOutboxSleepsWhileNothingIsDueTest.SleepingOutboxTestConfiguration.class
    },
    properties = "vanillabp.outbox.poll-interval=PT1H")
@DirtiesContext
@Testcontainers
public class MongoOutboxSleepsWhileNothingIsDueTest {

  /**
   * The collection the application writes its outbox into. The test asks the reader for it
   * and does not write it down here. So a rename in the platform is followed in one place.
   */
  private static final String OUTBOX_COLLECTION = MongoPhaseTwoOutboxReader.defaultOutboxCollectionName();

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  /**
   * The listener is added to the client of the application, so that client reports. A
   * client of the test itself would count nothing the application does.
   */
  static final CountingCommandListener commands = new CountingCommandListener();

  @TestConfiguration
  static class SleepingOutboxTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder
          .applyConnectionString(new ConnectionString(mongoDb.getReplicaSetUrl()))
          .addCommandListener(commands);
    }

  }

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  private long countEntriesNotDone() {

    return MongoPhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(mongoTemplate.getDb())
        .entriesWaiting();

  }

  /**
   * How long the store is watched after it went quiet. Three seconds is long enough for a
   * poller with its own rhythm to come back at least once. The shortest rhythm here is the
   * half second of <code>vanillabp.outbox.attempt-frequency</code>.
   * <p>
   * The number is not a budget anybody has to beat. The test then asserts that nothing was
   * asked at all. A busy machine which leaves this JVM without a turn only makes the
   * silence longer.
   */
  private static final long SILENCE_MEASURED_OVER_MS = 3000;

  /**
   * How long the collection must stay untouched before the dispatch counts as over. It is
   * below {@link #SILENCE_MEASURED_OVER_MS}. So a poller with a shorter rhythm than that is
   * caught by the wait and does not slip through it.
   */
  private static final long QUIET_FOR_MS = 500;

  private void awaitNothingLeftUndone() throws Exception {

    final var deadline = System.currentTimeMillis() + 30000;
    while (countEntriesNotDone() > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never dispatched");
      Thread.sleep(50);
    }

  }

  /**
   * Waits until the dispatch which emptied the store has stopped talking to the
   * collection.
   * <p>
   * When nothing is left OPEN, that dispatch is not over yet. The poll which marked the
   * entry looks for the next due one, deletes what the retention lets go, and reads when to
   * wake up again. Those commands arrive AFTER the wait above has returned. The test used
   * to forget what was sent in between. That made it red now and then, on runs where
   * nothing was wrong.
   * <p>
   * The wait reads the commands the listener collected. It asks the collection nothing
   * itself, because its own question would be the traffic it waits out. A store which
   * never goes quiet is exactly what this test is about. So the deadline says that, and
   * does not just time out without a word.
   */
  private void awaitTheDispatchWentQuiet() throws Exception {

    final var deadline = System.currentTimeMillis() + 30000;
    var commandsSeen = commands.commandsOn(OUTBOX_COLLECTION).size();
    var quietSince = System.currentTimeMillis();
    while ((System.currentTimeMillis() - quietSince) < QUIET_FOR_MS) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the outbox collection was asked something over and over while nothing was due");
      Thread.sleep(20);
      final var commandsNow = commands.commandsOn(OUTBOX_COLLECTION).size();
      if (commandsNow != commandsSeen) {
        commandsSeen = commandsNow;
        quietSince = System.currentTimeMillis();
      }
    }

  }

  @Test
  @DisplayName("The questions the poller asks are answered from an index")
  public void theQuestionsOfThePollerAreIndexed() {

    // without these, each question reads the whole collection. That cost grows with
    // everything the collection ever held, while the wake-ups stay just as rare.
    // The field names are written here and do not come from the reader, because this test
    // is about them. An index is a promise about a field. If a field is renamed without
    // its index, this assertion fails, and that is the whole point of it
    final var keys = MongoPhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(mongoTemplate.getDb())
        .indexKeys();

    assertTrue(
        keys.contains(new org.bson.Document("status", 1).append("nextAttemptAt", 1)),
        "the due question and the claim which picks an entry up read these two: "
            + keys);
    assertTrue(
        keys.contains(new org.bson.Document("status", 1).append("doneAt", 1)),
        "the retention question and its delete read these two: "
            + keys);
    assertTrue(
        keys.contains(new org.bson.Document("status", 1).append("createdAt", 1)),
        "the age of the oldest waiting entry is read from these two: "
            + keys);
    assertTrue(
        keys.contains(new org.bson.Document("args.payloadReference", 1)),
        "the housekeeping asks which of the expired payloads an entry still names, and reads this: "
            + keys);

  }

  @Test
  @DisplayName("Nothing is asked of the collection while the store owes nothing")
  public void aQuietStoreIsAskedNothing() throws Exception {

    listener.reset();
    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("quiet-store");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attached);
    awaitNothingLeftUndone();
    // the silence below means nothing unless this listener can see traffic at all
    assertFalse(
        commands.commandsOn(OUTBOX_COLLECTION).isEmpty(),
        "dispatching an entry has to show up here, or this test measures its own instrument");

    awaitTheDispatchWentQuiet();
    commands.reset();
    Thread.sleep(SILENCE_MEASURED_OVER_MS);

    assertEquals(
        List.of(),
        commands.commandsOn(OUTBOX_COLLECTION),
        "a store with nothing to do has to be left alone");

  }

}
