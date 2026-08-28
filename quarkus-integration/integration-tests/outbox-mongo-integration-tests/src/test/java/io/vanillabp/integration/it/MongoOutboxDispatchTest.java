package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Integration test of the MongoDB-based phase-two outbox on Quarkus using the dummy
 * adapter forced to require a two-phase commit
 * (<code>dummy-adapter.at-least-once-delivery: true</code>) against a STANDALONE
 * MongoDB started by Quarkus Dev Services (best-effort mode - the only mode of this outbox, see
 * {@code MongoPhaseTwoOutbox}):
 * <ul>
 *   <li>the entry is written immediately (best-effort: visible before the commit)
 *       and phase two is dispatched after the commit, marking the entry DONE
 *       (deleted asynchronously after the retention period only),</li>
 *   <li>on rollback the already-written entry is deleted best-effort and phase two
 *       is never dispatched,</li>
 *   <li>a duplicate schedule for the same aggregate is a no-op (partial unique
 *       index on the idempotency key),</li>
 *   <li>a failing dispatch is retried and</li>
 *   <li>a left-over entry (like after a crash) is dispatched by the recovery
 *       poller.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOutboxDispatchTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "outbox-dispatch-it");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  private MongoCollection<Document> outbox() {

    return mongoClient
        .getDatabase("outbox-dispatch-it")
        .getCollection("vanillabp-phase-two-outbox");

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
    while (outbox().countDocuments(new Document("status", new Document("$ne", "DONE"))) > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an outbox entry was never marked DONE");
      Thread.sleep(50);
    }

  }

  @BeforeEach
  public void reset() {

    listener.reset();
    outbox().deleteMany(new Document());

  }

  @Test
  @DisplayName("The entry is written best-effort, phase two dispatched after commit and the entry marked DONE")
  public void entryWrittenAndPhaseTwoDispatchedAfterCommit() throws Exception {

    userTransaction.begin();
    final Aggregate attachedAggregate;
    try {
      attachedAggregate = workflowService.startWorkflow("commit-test");
      // best-effort mode: the entry is written immediately, so it is visible
      // BEFORE the commit (no MongoDB transaction - documented window)
      assertEquals(1, outbox().countDocuments());
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();

    assertNotNull(attachedAggregate.getId());

    // after the commit, phase two has to be dispatched with the aggregate's ID
    // converted back from its string representation to the original type
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // DONE instead of delete: the entry stays visible until the retention cleanup
    final var deadline = System.currentTimeMillis() + 10000;
    while (outbox().countDocuments(new Document("status", "DONE")) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "outbox entry was not marked DONE");
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("On rollback the already-written entry is deleted best-effort and phase two never dispatched")
  public void rollbackDeletesEntryAndNoPhaseTwo() throws Exception {

    userTransaction.begin();
    workflowService.startWorkflow("rollback-test");
    // best-effort mode: the entry is already visible inside the transaction
    assertEquals(1, outbox().countDocuments());
    userTransaction.rollback();

    // the rollback handling deleted the entry best-effort
    assertEquals(0, outbox().countDocuments(), "the entry has to be deleted on rollback");

    // wait longer than the poll interval: phase two must never be dispatched
    Thread.sleep(1500);
    assertTrue(listener.getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A duplicate schedule while the first one is still pending is a no-op")
  public void duplicateScheduleAgainstAPendingEntryIsNoOp() throws Exception {

    // both starts ride ONE transaction, so nothing is dispatched in between and the
    // second one meets an entry which is still waiting: the unique index over the
    // dedup key makes it a no-op. Planning against a pending entry is what this test
    // WANTS, so it must not wait for the window to close
    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("dedup-pending");
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    listener.awaitInvocations(1, 10000);
    Thread.sleep(1500);
    assertEquals(1, listener.getInvocations().size(), "only one of the two starts was planned");
    assertEquals(1, outbox().countDocuments());

  }

  @Test
  @DisplayName("A repetition after the dispatch is a new operation - the key does not block it")
  public void aRepetitionAfterTheDispatchIsPlanned() throws Exception {

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("dedup-dispatched");
    userTransaction.commit();

    listener.awaitInvocations(1, 10000);
    awaitDeduplicationWindowClosed();

    // the dispatched entry took its own id as dedup key, so the same operation can be
    // planned again (see decision 22 in the repository's DECISIONS.md)
    userTransaction.begin();
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    listener.awaitInvocations(2, 10000);
    Thread.sleep(1500);
    assertEquals(2, listener.getInvocations().size(), "the repetition reached the BPMS");
    assertEquals(2, outbox().countDocuments());

  }

  @Test
  @DisplayName("A failing dispatch is retried")
  public void failingDispatchIsRetried() throws Exception {

    listener.failNextDispatches(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("retry-test");
    userTransaction.commit();

    // the first dispatch fails, the retry succeeds
    final var invocations = listener.awaitInvocations(2, 10000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));

  }

  @Test
  @DisplayName("A left-over entry (like after a crash) is dispatched by the recovery poller")
  public void leftOverEntryIsDispatchedByPoller() throws Exception {

    // simulate an entry committed by a crashed instance: this JVM's outbox never
    // saw it being scheduled, so only the poller can pick it up
    final var now = new Date();
    outbox().insertOne(new Document()
        .append("_id", UUID.randomUUID().toString())
        .append("workflowModuleId", "test-module")
        .append("bpmnProcessId", "WorkflowService")
        .append("operation", "START_WORKFLOW")
        .append("aggregateId", "4711")
        .append("adapterId", "test")
        .append("idempotencyKey", "START_WORKFLOW|test-module|WorkflowService|4711")
        .append("dedupKey", "START_WORKFLOW|test-module|WorkflowService|4711")
        .append("status", "OPEN")
        .append("createdAt", now)
        .append("attempts", 0)
        .append("nextAttemptAt", now));

    final var invocations = listener.awaitInvocations(1, 10000);
    // the aggregate ID was converted back to the aggregate's ID type (Long)
    assertEquals(4711L, invocations.getFirst());

  }

}
