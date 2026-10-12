package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * What the lease of the MongoDB store is for. A lease is a time-limited claim on an entry.
 * An entry which a node is dispatching stays with that node, however long the dispatch
 * takes. And the attempts count what was really attempted.
 * <p>
 * This store dispatches on its own lanes, so its poller keeps asking while a dispatch
 * runs. The live lease makes a poll leave the entry alone, the next poll of this node as
 * well as the poll of another node. The test plays another node. While a dispatch runs, it
 * asks the collection the question a poll asks. The answer must stay "nothing to take" for
 * the whole dispatch. Before the lease was renewed, the answer changed after one
 * <code>vanillabp.outbox.attempt-frequency</code> (half a second here). Then the operation
 * ran twice.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoDispatchOutlastingItsLeaseTest.MongoLeaseTestConfiguration.class
})
@DirtiesContext
@Testcontainers
public class MongoDispatchOutlastingItsLeaseTest {

  /**
   * How long one dispatch stays inside the adapter. That is six times the lease this
   * application configures, so every poll in that time used to take the entry.
   */
  private static final long A_DISPATCH_WHICH_TAKES_ITS_TIME = 3000;

  /**
   * How long a test waits before it says that nothing more happened. It is three of the
   * windows in which a second taker used to appear. It is a guard, not a measurement of
   * speed. A busy machine which leaves this JVM without a turn only makes the wait longer.
   * And the test then asserts a count which did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  private static final long UNTIL_IT_HAPPENED = 30_000;

  /**
   * How long the test asks its question while the dispatch runs. It is half the dispatch.
   * That is three leases, so three windows in which a second taker used to appear. It ends
   * well before the dispatch does. A question at the very end would race with the mark the
   * dispatch writes, and it would read the attempt counted there.
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
    store().removeAllEntries();

  }

  /**
   * Puts the delay back. Otherwise a class running after this one would dispatch slowly
   * for a reason nobody can see in that class.
   */
  @AfterEach
  public void letDispatchesBeFastAgain() {

    listener.reset();

  }

  /**
   * Writes an entry the way a crashed node left it behind. The outbox of this JVM never saw
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
    final var now = Instant.now();
    final var idempotencyKey = "START_WORKFLOW|test-module|SampleWorkflowService|%s".formatted(aggregateId);
    if (leasedBy == null) {
      store()
          .writeWaitingEntry(
              id, "test-module", "SampleWorkflowService", "START_WORKFLOW", aggregateId, "test", idempotencyKey, now,
              now);
    } else {
      // the entry is due at once, so it has to arrive together with its lease. Written first
      // and leased afterwards, it would be free for a moment, and the poller of this
      // application could take it then
      store()
          .writeLeasedWaitingEntry(
              id, "test-module", "SampleWorkflowService", "START_WORKFLOW", aggregateId, "test", idempotencyKey,
              leasedBy, leasedUntil, now, now);
    }
    return id;

  }

  /**
   * The store of the application. The test asks it through the reader, so no collection
   * and no field is named here.
   */
  private MongoPhaseTwoOutboxReader store() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoTemplate.getDb());

  }

  private MongoPhaseTwoOutboxReader.Entry entryOf(
      final String id) {

    return store()
        .entryById(id)
        .orElseThrow();

  }

  /**
   * The question a poll of ANOTHER instance asks: is there an entry which is due and which
   * nobody holds? This is a read, not the claim itself. So asking it changes nothing for
   * the dispatcher under test.
   *
   * @return Whether another node would take an entry right now
   */
  private boolean anotherNodeWouldTakeSomething() {

    return store().somethingIsDueAndUnleased();

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
    // the adapter is inside the dispatch now. Ask during the next three leases. The entry
    // must stay out of reach for every single question
    final var whileItIsRunning = System.currentTimeMillis() + WHILE_THE_DISPATCH_IS_CLEARLY_RUNNING;
    while (System.currentTimeMillis() < whileItIsRunning) {
      assertFalse(
          anotherNodeWouldTakeSomething(),
          "another node would have taken the entry while it was being dispatched");
      assertEquals(
          0,
          entryOf(entry).attempts(),
          "the claim counted an attempt although no attempt has ended");
      Thread.sleep(100);
    }

    waitUntil("the dispatch never finished", () -> entryOf(entry).wasDispatched());
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    assertEquals(1, listener.getInvocations().size(), "the operation reached the adapter more than once");
    assertEquals(
        1,
        entryOf(entry).attempts(),
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
    waitUntil("the entry was never marked", () -> store().entriesDispatched() == 1);
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    final var dispatched = store()
        .entriesDispatchedAlready()
        .getFirst();
    assertNotNull(dispatched);
    assertEquals(1, dispatched.attempts(), "an attempt which was made is an attempt which counts");

  }

  @Test
  @DisplayName("An entry whose holder died is taken over, one whose lease still runs is left alone")
  public void anEntryWhoseHolderDiedIsTakenOver() throws Exception {

    // both are due, and only the lease tells them apart
    final var leftBehind = anEntryDueNow(
        "left-behind-aggregate", "a-node-which-died", Instant.now().minus(Duration.ofMinutes(1)));
    final var heldBySomebodyElse = anEntryDueNow(
        "held-elsewhere-aggregate", "a-node-which-is-working", Instant.now().plus(Duration.ofHours(1)));

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    waitUntil("the entry of the node which died was never taken over", () -> entryOf(leftBehind).wasDispatched());
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);

    assertEquals(
        java.util.List.of("left-behind-aggregate"),
        listener.getInvocations(),
        "an entry whose lease is still running was dispatched as well");
    final var untouched = entryOf(heldBySomebodyElse);
    assertTrue(untouched.isWaiting(), "the leased entry was dispatched");
    assertEquals(0, untouched.attempts(), "the leased entry was attempted");

  }

}
