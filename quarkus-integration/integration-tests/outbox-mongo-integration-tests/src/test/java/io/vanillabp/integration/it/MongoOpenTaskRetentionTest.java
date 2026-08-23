package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches;
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
 * Story 97 in the MongoDB store on Quarkus: the record which answers the redeliveries of an
 * OPEN task outlives the retention as long as the BPMS keeps redelivering that task, and
 * the record of a task nobody hands out any more expires as it always did. The moment the
 * handler ran stays where it is, because the age of the open task is measured from it.
 * <p>
 * An hour of retention with records backdated by two, so nothing here waits for a clock -
 * unlike {@link MongoTaskDeliveryLogTest}, whose retention of zero cannot tell a record
 * kept from one deleted.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOpenTaskRetentionTest {

  private static final String DATABASE = "open-task-it";

  private static final String COLLECTION = "vanillabp-task-deliveries";

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
      .overrideConfigKey("vanillabp.outbox.retention", "PT1H");

  @Inject
  MongoTaskDeliveryLog deliveryLog;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  @BeforeEach
  public void clearRecords() {

    mongoClient
        .getDatabase(DATABASE)
        .getCollection(COLLECTION)
        .deleteMany(new Document());

  }

  /**
   * A record written the given time ago - both of its timestamps, exactly as the insert
   * writes them.
   */
  private TaskDelivery backdated(
      final String deliveryKey,
      final Duration age) {

    return new TaskDelivery(
        deliveryKey, "test-adapter", "test-module", "TestProcess", "4711", "awaitCompletion", "COMPLETION_PENDING", null, null, Instant
            .now()
            .minus(age));

  }

  private Document documentOf(
      final String deliveryKey) {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection(COLLECTION)
        .find(new Document("_id", deliveryKey))
        .first();

  }

  @Test
  @DisplayName("A redelivered open task keeps its record, one nobody redelivers loses it")
  public void aRedeliveredOpenTaskKeepsItsRecord() throws Exception {

    userTransaction.begin();
    deliveryLog.record(backdated("job-open", Duration.ofHours(2)));
    deliveryLog.record(backdated("job-forgotten", Duration.ofHours(2)));
    userTransaction.commit();

    // the BPMS redelivered the open task, which is what the core reports to the store
    deliveryLog.stillOpen("job-open");

    final var deleted = deliveryLog.cleanUpExpiredRecords();

    assertEquals(1, deleted);
    assertTrue(
        deliveryLog.recordedDelivery("job-open").isPresent(),
        "a task which is still being redelivered keeps the record answering it");
    assertTrue(
        deliveryLog.recordedDelivery("job-forgotten").isEmpty(),
        "a record nobody has seen for a whole retention expires");

    final var kept = documentOf("job-open");
    assertTrue(
        kept.getDate("lastSeenAt").after(kept.getDate("recordedAt")),
        "the moment it was last seen moved, the moment it was recorded did not");
    assertTrue(
        kept.getDate("recordedAt").toInstant().isBefore(Instant.now().minus(Duration.ofMinutes(90))),
        "so the age of the open task is still measured from the moment the handler ran");

  }

  @Test
  @DisplayName("More open tasks than one block are refreshed in blocks")
  public void moreOpenTasksThanOneBlockAreRefreshed() throws Exception {

    final var tasks = OpenTaskTouches.BLOCK_SIZE + 100;

    userTransaction.begin();
    for (var i = 0; i < tasks; i++) {
      deliveryLog.record(backdated("block-"
          + i, Duration.ofHours(2)));
    }
    userTransaction.commit();
    for (var i = 0; i < tasks; i++) {
      deliveryLog.stillOpen("block-"
          + i);
    }

    assertEquals(0, deliveryLog.cleanUpExpiredRecords(), "every one of them survives");
    assertTrue(
        documentOf("block-"
            + (tasks - 1)).getDate("lastSeenAt")
            .after(documentOf("block-"
                + (tasks - 1)).getDate("recordedAt")),
        "the last key of the last block was written as well");

  }

}
