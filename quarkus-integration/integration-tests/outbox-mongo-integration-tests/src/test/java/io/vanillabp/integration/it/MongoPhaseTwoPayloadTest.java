package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.PayloadExtension;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * A payload on the MongoDB store: the document with the bytes is written where the
 * outbox entry is written, the dispatch reads it back, and it is gone once the entry was
 * marked DONE.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoPhaseTwoPayloadTest {

  private static final String DATABASE = "outbox-payload-it";

  private static final String PAYLOAD_COLLECTION = "vanillabp-phase-two-payloads";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(PayloadExtension.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE);

  @Inject
  WorkflowService workflowService;

  @Inject
  PayloadExtension extension;

  @Inject
  PhaseTwoOutbox outbox;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  private MongoCollection<Document> payloads() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(PAYLOAD_COLLECTION);

  }

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  @Test
  @DisplayName("A payload travels by reference and is gone once the entry was dispatched")
  public void aPayloadTravelsByReference() throws Exception {

    final var state = "{\"amount\":42}".getBytes(StandardCharsets.UTF_8);

    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("payload");
    final var scheduled = PayloadExtension.call(aggregate.getId().toString(), "created", state);
    outbox.schedule(scheduled);
    userTransaction.commit();
    assertNotNull(aggregate.getId());

    final var dispatched = extension.awaitDispatched(1, 20000);
    final var call = dispatched.getFirst();
    assertEquals(scheduled.payloadReference(), call.payloadReference());
    assertArrayEquals(state, call.payload());

    final var deadline = System.currentTimeMillis() + 20000;
    while (payloads().countDocuments(new Document("_id", scheduled.payloadReference())) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the payload '%s' was never removed".formatted(scheduled.payloadReference()));
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A call without a payload writes no document into the payload collection")
  public void aCallWithoutAPayloadStoresNothing() throws Exception {

    final var before = payloads().countDocuments();

    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("no-payload");
    outbox.schedule(PayloadExtension.call(aggregate.getId().toString(), "completed", null));
    userTransaction.commit();
    assertNotNull(aggregate.getId());

    final var dispatched = extension.awaitDispatched(1, 20000);
    assertNull(dispatched.getFirst().payloadReference());
    assertEquals(before, payloads().countDocuments());

  }

}
