package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import io.vanillabp.integration.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The MongoDB store behind the inbound idempotency (story 51): a delivery record has to
 * ride the transaction which persists the workflow aggregate, be unique by its delivery
 * key and disappear once its retention period passed. The TestContainers MongoDB runs as
 * a replica set, so record and aggregate really share one MongoDB transaction.
 * <p>
 * That the CORE skips a handler for a recorded delivery is pinned by the platform's
 * acceptance test; what is tested here is the store.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = {
        TestApplication.class, MongoTaskDeliveryLogTest.MongoDeliveryLogTestConfiguration.class
    },
    // a retention of zero makes every record expired right away, so the cleanup can be
    // observed without waiting days for it
    properties = "vanillabp.outbox.retention=PT0S")
@Testcontainers
public class MongoTaskDeliveryLogTest {

  private static final String COLLECTION = "vanillabp-task-deliveries";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class MongoDeliveryLogTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
    }

  }

  @Autowired
  private MongoTaskDeliveryLog deliveryLog;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  public void clearRecords() {

    mongoTemplate.getCollection(COLLECTION).deleteMany(new org.bson.Document());

  }

  private TaskDelivery delivery(
      final String deliveryKey,
      final String outcome) {

    return new TaskDelivery(
        deliveryKey, "test-module", "TestProcess", "4711", "processTask", outcome, "PAYMENT_FAILED", "PaymentFailed");

  }

  @Test
  @DisplayName("A record is written with the transaction and read back with its outcome")
  public void aRecordIsWrittenAndReadBack() {

    transactionTemplate.executeWithoutResult(
        status -> assertTrue(deliveryLog.record(delivery("job-1", "BPMN_ERROR"))));

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
  public void aRolledBackTransactionLeavesNoRecord() {

    assertThrowsExactly(
        IllegalStateException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          deliveryLog.record(delivery("job-2", "COMPLETED"));
          throw new IllegalStateException("the handler failed after the record was written");
        }));

    assertTrue(
        deliveryLog.recordedDelivery("job-2").isEmpty(),
        "work and record commit together - or neither of them does");

  }

  @Test
  @DisplayName("Recording the same delivery twice is a no-op")
  public void aDuplicateRecordIsANoOp() {

    transactionTemplate.executeWithoutResult(
        status -> assertTrue(deliveryLog.record(delivery("job-3", "COMPLETED"))));
    transactionTemplate.executeWithoutResult(
        status -> assertFalse(
            deliveryLog.record(delivery("job-3", "COMPLETED")),
            "the delivery key is unique in the store"));

    assertEquals(1, mongoTemplate.getCollection(COLLECTION).countDocuments());

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
        deliveryKey, workflowModuleId, bpmnProcessId, workflowAggregateId, "processTask", "COMPLETED", null, null);

  }

  @Test
  @DisplayName("Story 76: an ended workflow releases its records - and only its own")
  public void theRecordsOfAnEndedWorkflowAreReleased() {

    transactionTemplate.executeWithoutResult(status -> {
      deliveryLog.record(deliveryOf("job-5", "test-module", "TestProcess", "4711"));
      deliveryLog.record(deliveryOf("job-6", "test-module", "TestProcess", "4711"));
      deliveryLog.record(deliveryOf("job-7", "test-module", "TestProcess", "4712"));
      deliveryLog.record(deliveryOf("job-8", "test-module", "OtherProcess", "4711"));
      deliveryLog.record(deliveryOf("job-9", "other-module", "TestProcess", "4711"));
    });

    final var released = deliveryLog
        // a moment safely after the records were written: a store's timestamp has
        // millisecond resolution, and the bound is strict
        .releaseRecordsOf("test-module", "TestProcess", "4711", java.time.Instant.now().plusSeconds(1));

    assertEquals(2, released);
    assertTrue(deliveryLog.recordedDelivery("job-5").isEmpty());
    assertTrue(deliveryLog.recordedDelivery("job-6").isEmpty());
    assertTrue(deliveryLog.recordedDelivery("job-7").isPresent(), "another aggregate keeps its records");
    assertTrue(deliveryLog.recordedDelivery("job-8").isPresent(), "another process keeps its records");
    assertTrue(deliveryLog.recordedDelivery("job-9").isPresent(), "another workflow module keeps its records");

  }

  @Test
  @DisplayName("Story 76: a record written after the end of the workflow survives the release")
  public void aRecordWrittenAfterTheNotificationSurvives() {

    // a moment safely before the record is written - see above on the resolution
    final var endOfTheWorkflow = java.time.Instant.now().minusSeconds(1);
    transactionTemplate
        .executeWithoutResult(
            status -> deliveryLog.record(deliveryOf("job-10", "test-module", "TestProcess", "4711")));

    // the delivery of a SECOND workflow on the same aggregate, processed after the first
    // one ended - the time bound is what keeps its record
    final var released = deliveryLog.releaseRecordsOf("test-module", "TestProcess", "4711", endOfTheWorkflow);

    assertEquals(0, released);
    assertTrue(deliveryLog.recordedDelivery("job-10").isPresent());

  }

  @Test
  @DisplayName("Records are deleted once the retention period passed")
  public void expiredRecordsAreDeleted() {

    transactionTemplate.executeWithoutResult(
        status -> deliveryLog.record(delivery("job-4", "COMPLETED")));

    // this context runs with a retention of zero, so the record is expired right away
    final var deleted = deliveryLog.cleanUpExpiredRecords();

    assertEquals(1, deleted);
    assertTrue(deliveryLog.recordedDelivery("job-4").isEmpty());

  }

}
