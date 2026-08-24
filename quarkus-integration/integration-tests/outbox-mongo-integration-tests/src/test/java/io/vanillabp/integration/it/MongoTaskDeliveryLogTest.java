package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
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
 * The MongoDB store behind the inbound idempotency on Quarkus. MongoDB is no
 * JTA resource here, so a record is written immediately and removed again when the
 * transaction does not commit - that best-effort compensation is what makes a rolled-back
 * delivery reach its handler again, and it is pinned here together with reading a record
 * back, the uniqueness of a delivery key and the retention cleanup.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoTaskDeliveryLogTest {

  private static final String DATABASE = "delivery-log-it";

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
      // a retention of zero makes every record expired right away, so the cleanup can be
      // observed without waiting days for it
      .overrideConfigKey("vanillabp.outbox.retention", "PT0S");

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
        .deleteMany(new org.bson.Document());

  }

  private TaskDelivery delivery(
      final String deliveryKey,
      final String outcome) {

    return new TaskDelivery(
        deliveryKey, "test-adapter", "test-module", "TestProcess", "4711", "processTask", outcome, "PAYMENT_FAILED", "PaymentFailed", java.time.Instant
            .now());

  }

  @Test
  @DisplayName("A record is read back with its outcome")
  public void aRecordIsWrittenAndReadBack() throws Exception {

    userTransaction.begin();
    assertTrue(deliveryLog.record(delivery("job-1", "BPMN_ERROR")));
    userTransaction.commit();

    final var recorded = deliveryLog.recordedDelivery("job-1");
    assertTrue(recorded.isPresent());
    assertEquals("test-module", recorded.get().workflowModuleId());
    assertEquals("TestProcess", recorded.get().bpmnProcessId());
    assertEquals("4711", recorded.get().workflowAggregateId());
    assertEquals("processTask", recorded.get().taskDefinition());
    assertEquals("BPMN_ERROR", recorded.get().outcome());
    assertEquals("PAYMENT_FAILED", recorded.get().bpmnErrorCode());
    assertEquals("PaymentFailed", recorded.get().bpmnErrorName());

  }

  @Test
  @DisplayName("A rolled-back transaction leaves no record")
  public void aRolledBackTransactionLeavesNoRecord() throws Exception {

    userTransaction.begin();
    deliveryLog.record(delivery("job-2", "COMPLETED"));
    userTransaction.rollback();

    assertTrue(
        deliveryLog.recordedDelivery("job-2").isEmpty(),
        "the record of work which was rolled back is removed again");

  }

  @Test
  @DisplayName("Recording the same delivery twice is a no-op")
  public void aDuplicateRecordIsANoOp() throws Exception {

    userTransaction.begin();
    assertTrue(deliveryLog.record(delivery("job-3", "COMPLETED")));
    userTransaction.commit();

    userTransaction.begin();
    assertFalse(
        deliveryLog.record(delivery("job-3", "COMPLETED")),
        "the delivery key is unique in the store");
    userTransaction.commit();

    assertEquals(
        1,
        mongoClient
            .getDatabase(DATABASE)
            .getCollection(COLLECTION)
            .countDocuments());

  }

  /**
   * A record of the given workflow, for the release which is bounded by exactly these
   * three values plus the moment it runs at.
   */
  private TaskDelivery deliveryOf(
      final String deliveryKey,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return new TaskDelivery(
        deliveryKey, "test-adapter", workflowModuleId, bpmnProcessId, workflowAggregateId, "processTask", "COMPLETED", null, null, java.time.Instant
            .now());

  }

  @Test
  @DisplayName("An ended workflow releases its records - and only its own")
  public void theRecordsOfAnEndedWorkflowAreReleased() throws Exception {

    userTransaction.begin();
    deliveryLog.record(deliveryOf("job-5", "test-module", "TestProcess", "4711"));
    deliveryLog.record(deliveryOf("job-6", "test-module", "TestProcess", "4711"));
    deliveryLog.record(deliveryOf("job-7", "test-module", "TestProcess", "4712"));
    deliveryLog.record(deliveryOf("job-8", "test-module", "OtherProcess", "4711"));
    deliveryLog.record(deliveryOf("job-9", "other-module", "TestProcess", "4711"));
    userTransaction.commit();

    userTransaction.begin();
    final var released = deliveryLog
        // a moment safely after the records were written: a store's timestamp has
        // millisecond resolution, and the bound is strict
        .releaseRecordsOf("test-module", "TestProcess", "4711", java.time.Instant.now().plusSeconds(1));
    userTransaction.commit();

    assertEquals(2, released);
    assertTrue(deliveryLog.recordedDelivery("job-5").isEmpty());
    assertTrue(deliveryLog.recordedDelivery("job-6").isEmpty());
    assertTrue(deliveryLog.recordedDelivery("job-7").isPresent(), "another aggregate keeps its records");
    assertTrue(deliveryLog.recordedDelivery("job-8").isPresent(), "another process keeps its records");
    assertTrue(deliveryLog.recordedDelivery("job-9").isPresent(), "another workflow module keeps its records");

  }

  @Test
  @DisplayName("A record written after the end of the workflow survives the release")
  public void aRecordWrittenAfterTheNotificationSurvives() throws Exception {

    // a moment safely before the record is written - see above on the resolution
    final var endOfTheWorkflow = java.time.Instant.now().minusSeconds(1);

    userTransaction.begin();
    deliveryLog.record(deliveryOf("job-10", "test-module", "TestProcess", "4711"));
    userTransaction.commit();

    // the delivery of a SECOND workflow on the same aggregate, processed after the first
    // one ended - the time bound is what keeps its record
    userTransaction.begin();
    final var released = deliveryLog.releaseRecordsOf("test-module", "TestProcess", "4711", endOfTheWorkflow);
    userTransaction.commit();

    assertEquals(0, released);
    assertTrue(deliveryLog.recordedDelivery("job-10").isPresent());

  }

  @Test
  @DisplayName("Records are deleted once the retention period passed")
  public void expiredRecordsAreDeleted() throws Exception {

    userTransaction.begin();
    deliveryLog.record(delivery("job-4", "COMPLETED"));
    userTransaction.commit();

    assertEquals(1, deliveryLog.cleanUpExpiredRecords());
    assertTrue(deliveryLog.recordedDelivery("job-4").isEmpty());

  }

}
