package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.bson.Document;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;
import com.mongodb.client.model.Filters;

import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What the lease of the MongoDB store is for: an entry somebody is dispatching stays that
 * node's entry however long the dispatch takes, and the attempts count what was attempted.
 * <p>
 * This store dispatches on lanes of its own, so its poller keeps asking while a dispatch is
 * under way. What makes a poll leave the entry alone is the live lease, this node's next poll
 * as much as another node's. The test plays another node: while a dispatch is under way it asks
 * the collection the question a poll asks, and the answer has to stay "nothing to take" for the
 * whole dispatch. Before the lease was renewed the answer flipped after one
 * <code>vanillabp.outbox.attempt-frequency</code>, half a second here, and the operation was
 * carried out twice.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoDispatchOutlastingItsLeaseTest.MongoLeaseTestConfiguration.class
})
@DirtiesContext
@Testcontainers
public class MongoDispatchOutlastingItsLeaseTest {

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  /**
   * How long one dispatch stays inside the adapter - six times the lease this application
   * configures, so every poll in that window used to take the entry.
   */
  private static final long A_DISPATCH_WHICH_TAKES_ITS_TIME = 3000;

  /**
   * How long a test waits before it says that nothing more happened. Three of the windows a
   * second taker used to appear in. It is a guard and not a measurement of speed: a machine
   * which leaves this JVM without a turn only makes the wait longer, and what is asserted
   * afterwards is a count which did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  private static final long UNTIL_IT_HAPPENED = 30_000;

  /**
   * How long the test asks its question while the dispatch is under way. Half the dispatch,
   * which is three leases and therefore three windows a second taker used to appear in, and it
   * ends well before the dispatch does - a question asked at the very end would race with the
   * mark the dispatch writes and read the attempt it counted there.
   */
  private static final long WHILE_THE_DISPATCH_IS_CLEARLY_RUNNING = A_DISPATCH_WHICH_TAKES_ITS_TIME / 2;

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class MongoLeaseTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {

      return builder -> builder.applyConnectionString(new ConnectionString(mongoDb.getReplicaSetUrl()));

    }

  }

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    mongoTemplate.getCollection(OUTBOX_COLLECTION).deleteMany(new Document());

  }

  /**
   * Puts the delay back, so a class running after this one is not dispatching slowly for a
   * reason nobody can see in it.
   */
  @AfterEach
  public void letDispatchesBeFastAgain() {

    listener.reset();

  }

  /**
   * Writes an entry the way a node which crashed left it behind: this JVM's outbox never saw
   * it being scheduled, so only a poll can pick it up.
   *
   * @param aggregateId The aggregate the operation belongs to
   * @param leasedBy Who holds the entry, <code>null</code> for an entry nobody holds
   * @param leasedUntil How long that hold lasts, <code>null</code> with the above
   * @return The entry's id
   */
  private String anEntryDueNow(
      final String aggregateId,
      final String leasedBy,
      final Instant leasedUntil) {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    final var key = "START_WORKFLOW|test-module|SampleWorkflowService|%s".formatted(aggregateId);
    mongoTemplate
        .getCollection(OUTBOX_COLLECTION)
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", "test-module")
            .append("bpmnProcessId", "SampleWorkflowService")
            .append("operation", "START_WORKFLOW")
            .append("aggregateId", aggregateId)
            .append("adapterId", "test")
            .append("idempotencyKey", key)
            .append("dedupKey", key)
            .append("status", "OPEN")
            .append("createdAt", now)
            .append("attempts", 0)
            .append("nextAttemptAt", now)
            .append("leasedBy", leasedBy)
            .append("leasedUntil", leasedUntil == null ? null : Date.from(leasedUntil)));
    return id;

  }

  private Document entryOf(
      final String id) {

    return mongoTemplate
        .getCollection(OUTBOX_COLLECTION)
        .find(Filters.eq("_id", id))
        .first();

  }

  /**
   * The question a poll of ANOTHER instance asks: is there an entry which is due and which
   * nobody holds? This is a read and not the claim itself, so asking it changes nothing for
   * the dispatcher under test.
   *
   * @return Whether another node would take an entry right now
   */
  private boolean anotherNodeWouldTakeSomething() {

    final var now = Date.from(Instant.now());
    return mongoTemplate
        .getCollection(OUTBOX_COLLECTION)
        .find(Filters
            .and(
                Filters.eq("status", "OPEN"),
                Filters.lte("nextAttemptAt", now),
                Filters
                    .or(
                        Filters.eq("leasedUntil", null),
                        Filters.lte("leasedUntil", now))))
        .first() != null;

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
  @DisplayName("No other node can take an entry while it is being dispatched, however long that takes")
  public void aSlowDispatchStaysWithTheNodeCarryingIt() throws Exception {

    listener.eachDispatchTakes(A_DISPATCH_WHICH_TAKES_ITS_TIME);
    final var entry = anEntryDueNow("slow-dispatch-aggregate", null, null);

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    // the adapter is inside the dispatch now. Ask over the next three leases, and the entry
    // has to stay out of reach for every single question
    final var whileItIsRunning = System.currentTimeMillis() + WHILE_THE_DISPATCH_IS_CLEARLY_RUNNING;
    while (System.currentTimeMillis() < whileItIsRunning) {
      assertFalse(
          anotherNodeWouldTakeSomething(),
          "another node would have taken the entry while it was being dispatched");
      assertEquals(
          0,
          entryOf(entry).getInteger("attempts"),
          "the claim counted an attempt although no attempt has ended");
      Thread.sleep(100);
    }

    waitUntil("the dispatch never finished", () -> "DONE".equals(entryOf(entry).getString("status")));
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    assertEquals(1, listener.getInvocations().size(), "the operation reached the adapter more than once");
    assertEquals(
        1,
        entryOf(entry).getInteger("attempts"),
        "one attempt was made, so one attempt is what the entry counts");

  }

  @Test
  @DisplayName("An entry dispatched right away counts its one attempt too")
  public void attemptsCountWhatWasAttempted() throws Exception {

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("one attempt");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    waitUntil(
        "the entry was never marked",
        () -> mongoTemplate
            .getCollection(OUTBOX_COLLECTION)
            .countDocuments(Filters.eq("status", "DONE")) == 1);
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    final var dispatched = mongoTemplate
        .getCollection(OUTBOX_COLLECTION)
        .find(Filters.eq("status", "DONE"))
        .first();
    assertNotNull(dispatched);
    assertEquals(1, dispatched.getInteger("attempts"), "an attempt which was made is an attempt which counts");

  }

  @Test
  @DisplayName("An entry whose holder died is taken over, one whose lease still runs is left alone")
  public void anEntryWhoseHolderDiedIsTakenOver() throws Exception {

    // both are due, and the lease is the only thing telling them apart
    final var leftBehind = anEntryDueNow(
        "left-behind-aggregate", "a-node-which-died", Instant.now().minus(Duration.ofMinutes(1)));
    final var heldBySomebodyElse = anEntryDueNow(
        "held-elsewhere-aggregate", "a-node-which-is-working", Instant.now().plus(Duration.ofHours(1)));

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    waitUntil("the entry of the node which died was never taken over", () -> "DONE".equals(entryOf(leftBehind)
        .getString("status")));
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    assertEquals(
        java.util.List.of("left-behind-aggregate"),
        listener.getInvocations(),
        "an entry whose lease is still running was dispatched as well");
    final var untouched = entryOf(heldBySomebodyElse);
    assertEquals("OPEN", untouched.getString("status"), "the leased entry was dispatched");
    assertEquals(0, untouched.getInteger("attempts"), "the leased entry was attempted");

  }

}
