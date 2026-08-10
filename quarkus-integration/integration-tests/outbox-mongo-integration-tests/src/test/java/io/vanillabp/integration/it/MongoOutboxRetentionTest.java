package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bson.Document;
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
 * Retention cleanup of the MongoDB-based phase-two outbox: with a tiny
 * <code>vanillabp.outbox.retention</code> a successfully dispatched (DONE) entry is
 * deleted asynchronously by the poller once the retention period passed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOutboxRetentionTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "outbox-retention-it")
      .overrideConfigKey("vanillabp.outbox.retention", "PT1S");

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
        .getDatabase("outbox-retention-it")
        .getCollection("vanillabp-phase-two-outbox");

  }

  @Test
  @DisplayName("A DONE entry is deleted asynchronously once the retention period passed")
  public void doneEntryIsDeletedAfterRetention() throws Exception {

    userTransaction.begin();
    workflowService.startWorkflow("retention-test");
    userTransaction.commit();

    listener.awaitInvocations(1, 10000);

    // retention PT1S + poll interval PT0.5S: the DONE entry has to be gone soon
    final var deadline = System.currentTimeMillis() + 10000;
    while (outbox().countDocuments() > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "DONE outbox entry was not deleted after the retention period");
      Thread.sleep(100);
    }

  }

}
