package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
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

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Integration test of the MongoDB phase-two outbox using the dummy adapter forced to
 * require a two-phase commit (<code>dummy-adapter.at-least-once-delivery: true</code>). The
 * TestContainers MongoDB runs as a replica set, so aggregate and outbox entry are
 * written in one MongoDB transaction:
 * <ul>
 *   <li>the outbox entry is enlisted in the local transaction (gone on rollback),</li>
 *   <li>phase two is dispatched after the commit and the entry is marked DONE
 *       (deleted asynchronously after the retention period only),</li>
 *   <li>a duplicate schedule for the same aggregate is a no-op,</li>
 *   <li>a failing dispatch is retried and</li>
 *   <li>a left-over entry (e.g. of a crashed instance) is dispatched by the
 *       poller.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoOutboxDispatchTest.MongoOutboxTestConfiguration.class
})
@Testcontainers
public class MongoOutboxDispatchTest {

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
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

  @BeforeEach
  public void resetListenerAndOutbox() {

    listener.reset();
    // remove entries possibly left over from previous tests
    mongoTemplate.getCollection(OUTBOX_COLLECTION).deleteMany(new org.bson.Document());

  }

  private long countOutboxEntries() {

    return mongoTemplate.getCollection(OUTBOX_COLLECTION).countDocuments();

  }

  private long countDoneOutboxEntries() {

    return mongoTemplate
        .getCollection(OUTBOX_COLLECTION)
        .countDocuments(new org.bson.Document("status", "DONE"));

  }

  /**
   * Waits until no entry deduplicates any more.
   * <p>
   * The listener runs INSIDE the dispatch, one update before the dispatcher sets the
   * status to DONE and frees the dedup key. A repetition planned in that window meets
   * an entry which is still waiting and is discarded - correct behaviour, and the
   * reason a test may not take the listener as the signal that the first operation is
   * over.
   */
  private void awaitDeduplicationWindowClosed() throws Exception {

    final var deadline = System.currentTimeMillis() + 10000;
    while (countOutboxEntries() > countDoneOutboxEntries()) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never marked DONE");
      Thread.sleep(50);
    }

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

    // DONE instead of delete: the entry has to be marked DONE after the successful
    // dispatch and stays visible until the asynchronous retention cleanup
    final var deadline = System.currentTimeMillis() + 10000;
    while (countDoneOutboxEntries() == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "outbox entry was not marked DONE");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A duplicate schedule while the first one is still pending is a no-op")
  public void duplicateScheduleAgainstAPendingEntryIsNoOp() throws Exception {

    // both starts ride ONE transaction, so the second one meets an entry which is
    // still waiting for its dispatch: the unique index over the dedup key makes it a
    // no-op. Planning against a pending entry is what this test WANTS, so it must not
    // wait for the window to close
    final var attachedAggregate = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("dedup-pending");
      final var started = processService.startWorkflow(aggregate);
      return processService.startWorkflow(started);
    });
    assertNotNull(attachedAggregate);
    listener.awaitInvocations(1, 10000);

    Thread.sleep(1500);
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

    // the dispatched entry took its own id as dedup key, so the same operation can be
    // planned again (see decision 22 in the repository's DECISIONS.md)
    transactionTemplate.execute(status -> processService.startWorkflow(attachedAggregate));

    listener.awaitInvocations(2, 10000);
    Thread.sleep(1500);
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

    // the entry must be gone since it was enlisted in the rolled-back transaction
    assertEquals(0, countOutboxEntries());

    // wait longer than the poll interval: phase two must never be dispatched
    Thread.sleep(1500);
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

    // simulate an entry committed by a crashed instance: this JVM's outbox never saw
    // it being scheduled, so only the poller can pick it up
    final var now = Instant.now();
    final var entry = new org.bson.Document()
        .append("_id", UUID.randomUUID().toString())
        .append("workflowModuleId", "test-module")
        .append("bpmnProcessId", "SampleWorkflowService")
        .append("operation", "START_WORKFLOW")
        .append("aggregateId", "left-over-aggregate")
        .append("adapterId", "test")
        .append("idempotencyKey", "START_WORKFLOW|test-module|SampleWorkflowService|left-over-aggregate")
        .append("dedupKey", "START_WORKFLOW|test-module|SampleWorkflowService|left-over-aggregate")
        .append("status", "OPEN")
        .append("createdAt", Date.from(now))
        .append("attempts", 0)
        .append("nextAttemptAt", Date.from(now));
    mongoTemplate.getCollection(OUTBOX_COLLECTION).insertOne(entry);

    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals("left-over-aggregate", invocations.getFirst());

  }

}
