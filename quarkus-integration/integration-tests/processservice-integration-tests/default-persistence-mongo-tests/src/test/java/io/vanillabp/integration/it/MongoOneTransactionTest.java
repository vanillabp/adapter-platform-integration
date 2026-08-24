package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bson.Document;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.persistence.ActiveTaskAwarenessSource;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregate;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.MongoRepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.PhaseTwoRecorder;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Acceptance test on Quarkus: an application storing EVERYTHING in MongoDB - the
 * aggregate through Panache, the phase-two outbox and the log of processed task deliveries
 * through VanillaBP's MongoDB defaults - with no data source anywhere.
 * <p>
 * What is pinned here is the atomicity the outbox contract promises. MongoDB Panache enlists
 * itself in the JTA transaction VanillaBP opens and starts a MongoDB transaction, and the
 * outbox writes through that very session. So while the transaction runs, a
 * reader outside it sees NOTHING - neither the aggregate nor the outbox entry - and after the
 * commit it sees both. Before the outbox joined the session, the entry was written
 * immediately and this test failed on the very first assertion.
 * <p>
 * The Dev Services MongoDB runs as a replica set, which MongoDB transactions require.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOneTransactionTest {

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  private static final String DATABASE = "one-transaction-it";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(MongoRepositoryAggregate.class)
          .addClass(MongoRepositoryAggregateRepository.class)
          .addClass(MongoRepositoryWorkflowService.class)
          .addClass(PhaseTwoRecorder.class)
          .addClass(ActiveTaskAwarenessSource.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("dummy-adapter.two-phase-commit", "true")
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideConfigKey("vanillabp.outbox.poll-interval", "PT0.5S")
      .overrideConfigKey("vanillabp.outbox.attempt-frequency", "PT0.5S");

  @Inject
  MongoRepositoryWorkflowService workflowService;

  @Inject
  MongoRepositoryAggregateRepository repository;

  @Inject
  PhaseTwoRecorder recorder;

  @Inject
  MongoClient mongoClient;

  /**
   * Counts what a reader OUTSIDE the running transaction sees - the point of the test.
   */
  private long outboxEntriesVisibleOutside() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(OUTBOX_COLLECTION)
        .countDocuments(new Document("aggregateId", "one-transaction"));

  }

  private long aggregatesVisibleOutside() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection("MongoRepositoryAggregate")
        .countDocuments(new Document("_id", "one-transaction"));

  }

  @Test
  @DisplayName("Aggregate and outbox entry are written in ONE MongoDB transaction")
  public void aggregateAndOutboxEntryShareOneMongoTransaction() {

    QuarkusTransaction
        .requiringNew()
        .run(() -> {
          workflowService.startWorkflow("one-transaction");

          // still inside the transaction: nothing of this is visible to anybody else
          assertEquals(
              0,
              outboxEntriesVisibleOutside(),
              "the outbox entry was written outside the MongoDB transaction of the aggregate");
          assertEquals(0, aggregatesVisibleOutside(), "the aggregate was written before the commit");
        });

    // after the commit both are there
    assertEquals(1, outboxEntriesVisibleOutside(), "the outbox entry did not survive the commit");
    assertNotNull(repository.findById("one-transaction"));

  }

  @Test
  @DisplayName("A rollback takes the aggregate and the outbox entry with it")
  public void rollbackRemovesBoth() {

    assertThrows(
        RuntimeException.class,
        () -> QuarkusTransaction
            .requiringNew()
            .run(() -> {
              workflowService.startWorkflow("rolled-back");
              throw new RuntimeException("no commit for this one");
            }));

    assertNull(repository.findById("rolled-back"), "the aggregate survived a rolled-back transaction");
    assertEquals(
        0,
        mongoClient
            .getDatabase(DATABASE)
            .getCollection(OUTBOX_COLLECTION)
            .countDocuments(new Document("aggregateId", "rolled-back")),
        "the outbox entry survived a rolled-back transaction");

  }

}
