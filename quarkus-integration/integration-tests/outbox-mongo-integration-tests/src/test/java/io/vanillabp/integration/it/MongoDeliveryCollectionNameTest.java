package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.runtime.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * An application which has naming rules for its collections renames the delivery log the
 * way it renames the outbox: <code>vanillabp.outbox.mongo.delivery-collection</code>.
 * <p>
 * The name is followed rather than read back: the record has to land in the configured
 * collection, the indexes have to be created on the same one, and the default name must
 * not exist at all. A wiring which read the property and then wrote to the constant would
 * pass a test asserting the property and fail this one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoDeliveryCollectionNameTest {

  private static final String DATABASE = "delivery-collection-name-it";

  private static final String RENAMED = "deliveries-of-mine";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideConfigKey("vanillabp.outbox.mongo.delivery-collection", RENAMED);

  @Inject
  MongoTaskDeliveryLog deliveryLog;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  @Test
  @DisplayName("The record is written into the configured collection, and the default one is never created")
  public void theRecordLandsInTheConfiguredCollection() throws Exception {

    userTransaction.begin();
    assertTrue(
        deliveryLog
            .record(
                new TaskDelivery("job-1", "test-adapter", "test-module", "TestProcess", "4711", "workflow-4711", "processTask", "Activity_processTask", null, "COMPLETED", null, null, java.time.Instant
                    .now(), null)));
    userTransaction.commit();

    final var database = mongoClient.getDatabase(DATABASE);
    assertEquals(
        1,
        database.getCollection(RENAMED).countDocuments(),
        "the document lies where the application asked for it");
    assertTrue(
        deliveryLog.recordedDelivery("job-1").isPresent(),
        "and the log reads it back out of the same collection");

    final var collections = new ArrayList<String>();
    database.listCollectionNames().forEach(collections::add);
    assertFalse(
        collections.contains(
            PhaseTwoOutboxProperties.MongoOutboxProperties.DEFAULT_DELIVERY_COLLECTION),
        "nothing touched the default name, so no second collection was created");

  }

  @Test
  @DisplayName("The indexes are created on the configured collection")
  public void theIndexesFollowTheName() {

    final var indexedFields = new ArrayList<String>();
    mongoClient
        .getDatabase(DATABASE)
        .getCollection(RENAMED)
        .listIndexes()
        .forEach(index -> indexedFields.addAll(index.get("key", org.bson.Document.class).keySet()));

    assertTrue(
        indexedFields.containsAll(
            java.util.List.of("lastSeenAt", "taskId", "aggregateId", "workflowId")),
        "an index on a collection nobody writes to would cost the reads it was made for");

  }

}
