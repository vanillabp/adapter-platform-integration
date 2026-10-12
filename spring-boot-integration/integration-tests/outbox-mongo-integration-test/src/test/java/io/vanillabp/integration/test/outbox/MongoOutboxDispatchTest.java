package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

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
 * Integration test of the MongoDB phase-two outbox. The dummy adapter is forced to need a
 * two-phase commit (<code>dummy-adapter.at-least-once-delivery: true</code>). The
 * Testcontainers MongoDB runs as a replica set, so aggregate and outbox entry are written
 * in one MongoDB transaction:
 * <ul>
 *   <li>the outbox entry is part of the local transaction (a rollback removes it),</li>
 *   <li>phase two is dispatched after the commit, and the entry is marked DONE (it is
 *       deleted later in the background, only after the retention period),</li>
 *   <li>a duplicate schedule for the same aggregate is a no-op,</li>
 *   <li>a failing dispatch is retried and</li>
 *   <li>a left-over entry (e.g. of a crashed instance) is dispatched by the
 *       poller and</li>
 *   <li>the store says how long the oldest entry waiting for its dispatch has been
 *       waiting.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoOutboxDispatchTest.MongoOutboxTestConfiguration.class
})
@Testcontainers
public class MongoOutboxDispatchTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>. These tests set it
   * to half a second, so this is three of those windows.
   * <p>
   * It is a guard, not a measurement of speed. A busy machine which leaves this JVM
   * without a turn only makes the wait longer. And the test then asserts a count which did
   * not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;


  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class MongoOutboxTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
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

  @Autowired
  private io.vanillabp.integration.spi.PhaseTwoOutbox outbox;

  @BeforeEach
  public void resetListenerAndOutbox() {

    listener.reset();
    store().removeAllEntries();

  }

  /**
   * The store of the application. The test asks it through the reader, so no collection
   * and no field is named here.
   */
  private MongoPhaseTwoOutboxReader store() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoTemplate.getDb());

  }

  private long countOutboxEntries() {

    return store().entriesAtAll();

  }

  private long countDoneOutboxEntries() {

    return store().entriesDispatched();

  }

  /**
   * Waits until no entry deduplicates any more.
   * <p>
   * The listener runs INSIDE the dispatch, one update before the dispatcher sets the
   * status to DONE and frees the dedup key. A repetition planned in that window meets an
   * entry which is still waiting, and it is thrown away. That is correct. It is also why a
   * test must not take the listener as the signal that the first operation is over.
   */
  private void awaitDeduplicationWindowClosed() throws Exception {

    final var deadline = System.currentTimeMillis() + 10000;
    while (countOutboxEntries() > countDoneOutboxEntries()) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never marked DONE");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("The age of the oldest waiting entry is the one of the oldest, not of the youngest")
  public void theOldestWaitingEntryDecidesTheAge() {

    assertEquals(
        java.time.Duration.ZERO,
        outbox
            .ageOfOldestPendingCall()
            .orElseThrow(),
        "nothing waits, and that zero says the outbox owes nothing");

    final var now = Instant.now();
    writeWaitingEntry("younger", now.minus(java.time.Duration.ofMinutes(1)));
    writeWaitingEntry("older", now.minus(java.time.Duration.ofMinutes(10)));

    assertTrue(
        outbox
            .ageOfOldestPendingCall()
            .orElseThrow()
            .toSeconds() >= 600L,
        "the store answers for the entry which has been waiting longest");

  }

  /**
   * Writes an entry which stays where it is. Its next attempt is an hour ahead, so the
   * poll of the dispatcher does not pick it up.
   *
   * @param id The id of the entry
   * @param writtenAt When it was written
   */
  private void writeWaitingEntry(
      final String id,
      final Instant writtenAt) {

    store()
        .writeWaitingEntry(
            id,
            "test-module",
            "Test",
            "START_WORKFLOW",
            "4711",
            "test",
            writtenAt,
            Instant.now().plus(java.time.Duration.ofHours(1)));

  }

  @Test
  @DisplayName("Phase two is dispatched after commit and the entry is marked DONE")
  public void phaseTwoDispatchedAfterCommit() throws Exception {

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("commit-test");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);
    assertNotNull(attachedAggregate.getId());

    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // DONE instead of delete: the entry must be marked DONE after the successful
    // dispatch. It stays visible until the retention cleanup removes it in the background
    final var deadline = System.currentTimeMillis() + 10000;
    while (countDoneOutboxEntries() == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "outbox entry was not marked DONE");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A duplicate schedule while the first one is still pending is a no-op")
  public void duplicateScheduleAgainstAPendingEntryIsNoOp() throws Exception {

    // both starts run in ONE transaction. So the second one meets an entry which still
    // waits for its dispatch, and the unique index over the dedup key makes it a no-op.
    // This test WANTS to plan against a waiting entry, so it must not wait for the window
    // to close
    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("dedup-pending");
      final var started = processService.startWorkflow(aggregate);
      return processService.startWorkflow(started);
    });
    assertNotNull(attachedAggregate);
    listener.awaitInvocations(1, 10000);

    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(1, listener.getInvocations().size(), "only one of the two starts was planned");
    assertEquals(1, countOutboxEntries());

  }

  @Test
  @DisplayName("A repetition after the dispatch is a new operation - the key does not block it")
  public void aRepetitionAfterTheDispatchIsPlanned() throws Exception {

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("dedup-dispatched");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);
    listener.awaitInvocations(1, 10000);
    awaitDeduplicationWindowClosed();

    // the dispatched entry took its own id as dedup key. So the same operation can be
    // planned again (see decision 22 in the repository's DECISIONS.md)
    transactionTemplate.execute(status -> processService.startWorkflow(attachedAggregate));

    listener.awaitInvocations(2, 10000);
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(2, listener.getInvocations().size(), "the repetition reached the BPMS");
    assertEquals(2, countOutboxEntries());

  }

  @Test
  @DisplayName("On rollback no outbox entry remains and phase two is never dispatched")
  public void rollbackLeavesNoEntryAndNoPhaseTwo() throws Exception {

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("rollback-test");
          processService.startWorkflow(aggregate);
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    // the entry must be gone, because it was part of the transaction which was rolled back
    assertEquals(0, countOutboxEntries());

    // wait longer than the poll interval: phase two must never be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(listener.getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A failing dispatch is retried")
  public void failingDispatchIsRetried() throws Exception {

    listener.failNextDispatches(1);

    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("retry-test");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);

    // the first dispatch fails, the retry succeeds
    final var invocations = listener.awaitInvocations(2, 10000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));

  }

  @Test
  @DisplayName("A left-over entry (like after a crash) is dispatched by the poller")
  public void leftOverEntryIsDispatchedByPoller() throws Exception {

    // plays an entry committed by a crashed instance. The outbox of this JVM never saw it
    // being scheduled, so only the poller can pick it up
    final var now = Instant.now();
    store()
        .writeWaitingEntry(
            UUID.randomUUID().toString(),
            "test-module",
            "SampleWorkflowService",
            "START_WORKFLOW",
            "left-over-aggregate",
            "test",
            "START_WORKFLOW|test-module|SampleWorkflowService|left-over-aggregate",
            now,
            now);

    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals("left-over-aggregate", invocations.getFirst());

  }

}
