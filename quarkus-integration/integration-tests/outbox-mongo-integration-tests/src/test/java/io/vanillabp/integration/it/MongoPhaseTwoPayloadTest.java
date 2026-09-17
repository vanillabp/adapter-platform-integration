package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * marked DONE. And the whole way of a younger report which takes the place of one still
 * waiting - one dispatch, the younger bytes, one payload document left.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoPhaseTwoPayloadTest {

  private static final String DATABASE = "outbox-payload-it";

  private static final String PAYLOAD_COLLECTION = "vanillabp-phase-two-payloads";

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

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

  private MongoCollection<Document> entries() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(OUTBOX_COLLECTION);

  }

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  private static byte[] payloadOf(
      final String content) {

    return content.getBytes(StandardCharsets.UTF_8);

  }

  @Test
  @DisplayName("The younger report replaces the waiting one, and only it is dispatched")
  public void theYoungerReportReplacesTheWaitingOne() throws Exception {

    // both ride ONE transaction on purpose: nothing is dispatched before it commits,
    // so the second call meets an entry which is certainly still waiting
    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("replace-mongo");
    final var first = PayloadExtension
        .call(aggregate.getId().toString(), "replaced", payloadOf("{\"amount\":1}"));
    assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(first));
    final var second = PayloadExtension
        .call(aggregate.getId().toString(), "replaced", payloadOf("{\"amount\":2}"));
    assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(second));
    userTransaction.commit();

    // one entry under that key, and the bytes of the replaced call are gone
    assertEquals(1L, entries().countDocuments(new Document("dedupKey", first.idempotencyKey().orElseThrow())));
    assertEquals(0L, payloads().countDocuments(new Document("_id", first.payloadReference())));

    final var dispatched = extension.awaitDispatched(1, 20000);
    assertArrayEquals(payloadOf("{\"amount\":2}"), dispatched.getFirst().payload());
    assertEquals(second.payloadReference(), dispatched.getFirst().payloadReference());

    // and the one which was dispatched is the only one there ever was
    Thread.sleep(1500);
    assertEquals(1, extension.awaitDispatched(1, 1000).size());

  }

  @Test
  @DisplayName("Without the word the older report stays and the younger one is dropped")
  public void withoutTheWordTheOlderReportStays() throws Exception {

    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("no-replace-mongo");
    final var first = PayloadExtension.call(aggregate.getId().toString(), "kept", payloadOf("{\"amount\":1}"));
    assertTrue(outbox.schedule(first));
    final var second = PayloadExtension.call(aggregate.getId().toString(), "kept", payloadOf("{\"amount\":2}"));
    assertFalse(outbox.schedule(second));
    userTransaction.commit();

    // a schedule which was discarded leaves nothing behind
    assertEquals(0L, payloads().countDocuments(new Document("_id", second.payloadReference())));

    final var dispatched = extension.awaitDispatched(1, 20000);
    assertArrayEquals(payloadOf("{\"amount\":1}"), dispatched.getFirst().payload());
    assertEquals(first.payloadReference(), dispatched.getFirst().payloadReference());

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
