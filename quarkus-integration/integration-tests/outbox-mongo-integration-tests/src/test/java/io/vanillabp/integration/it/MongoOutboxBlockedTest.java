package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Poison-entry path of the MongoDB-based phase-two outbox: after
 * <code>vanillabp.outbox.block-after-attempts</code> (here: 2) failed dispatches
 * the entry is marked BLOCKED, left as a monitorable trail and never retried again.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOutboxBlockedTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "outbox-blocked-it")
      .overrideConfigKey("vanillabp.outbox.block-after-attempts", "2");

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
        .getDatabase("outbox-blocked-it")
        .getCollection("vanillabp-phase-two-outbox");

  }

  @Test
  @DisplayName("A permanently failing entry is BLOCKED after the configured attempts")
  public void permanentlyFailingEntryIsBlocked() throws Exception {

    listener.failNextDispatches(Integer.MAX_VALUE);

    userTransaction.begin();
    workflowService.startWorkflow("blocked-test");
    userTransaction.commit();

    // wait until the entry is marked BLOCKED
    final var deadline = System.currentTimeMillis() + 10000;
    while (outbox().countDocuments(new Document("status", "BLOCKED")) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "entry was not blocked");
      Thread.sleep(50);
    }

    // exactly block-after-attempts dispatches happened, then no more
    assertEquals(2, listener.getInvocations().size());
    Thread.sleep(1500);
    assertEquals(2, listener.getInvocations().size(), "a BLOCKED entry must not be retried");

  }

}
