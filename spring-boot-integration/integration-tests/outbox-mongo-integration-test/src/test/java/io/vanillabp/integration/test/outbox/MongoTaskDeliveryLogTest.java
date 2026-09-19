package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The MongoDB store behind the inbound idempotency: a delivery record has to
 * ride the transaction which persists the workflow aggregate, be unique by its delivery
 * key and disappear once its retention period passed. The TestContainers MongoDB runs as
 * a replica set, so record and aggregate really share one MongoDB transaction.
 * <p>
 * That the CORE skips a handler for a recorded delivery is pinned by the platform's
 * acceptance test; what is tested here is the store.
 * <p>
 * What a cleanup deleted is read back as state rather than counted, because the cleanup
 * running in the background of this application deletes the same records - see decision 46
 * in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = {
        TestApplication.class, MongoTaskDeliveryLogTest.MongoDeliveryLogTestConfiguration.class
    },
    // a retention longer than anything these tests write, so the cleanup running in the
    // background of this application never deletes a record a test is looking at. A test
    // which needs an expired record brings a log of its own with the retention it needs
    properties = "vanillabp.outbox.retention=PT24H")
@Testcontainers
public class MongoTaskDeliveryLogTest {

  private static final String COLLECTION = "vanillabp-task-deliveries";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
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

    return new TaskDelivery(deliveryKey, "test-adapter", "test-module", "TestProcess", "4711", "workflow-4711", "processTask", "Activity_processTask", null, outcome, "PAYMENT_FAILED", "PaymentFailed", java.time.Instant
        .now(), null);

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
    // the element of the model and the workflow of the BPMS: written into the document and
    // read back out of it, which is all this store owes them
    assertEquals("Activity_processTask", recorded.get().bpmnElementId());
    assertEquals("workflow-4711", recorded.get().workflowId());

  }

  @Test
  @DisplayName("The record of an open task is found by that task, and says when it was closed")
  public void theRecordOfATaskAnswersTheElection() {

    transactionTemplate.executeWithoutResult(
        status -> deliveryLog
            .record(
                new TaskDelivery("job-open", "test-adapter", "test-module", "TestProcess", "4711", "workflow-4711", "awaitCompletion", "Activity_awaitCompletion", "task-77", "COMPLETION_PENDING", null, null, java.time.Instant
                    .now(), null)));

    final var open = deliveryLog.recordOfTask("test-module", "TestProcess", "4711", "task-77");
    assertTrue(open.isPresent(), "this is what a completeTask reads instead of asking a BPMS");
    assertEquals("test-adapter", open.get().adapterId());
    assertNull(open.get().taskClosedAt(), "a record is born open");
    assertEquals("Activity_awaitCompletion", open.get().bpmnElementId());
    assertEquals("workflow-4711", open.get().workflowId());
    assertTrue(
        deliveryLog.recordOfTask("test-module", "TestProcess", "4711", "another-task").isEmpty(),
        "another task is another question");

    assertEquals(1, deliveryLog.markTaskClosed("test-module", "TestProcess", "4711", "task-77"));

    assertNotNull(
        deliveryLog.recordOfTask("test-module", "TestProcess", "4711", "task-77").orElseThrow().taskClosedAt());
    assertEquals(
        0,
        deliveryLog.markTaskClosed("test-module", "TestProcess", "4711", "task-77"),
        "the task was closed when it was first closed");

  }

  @Test
  @DisplayName("Every record naming one task is closed, not only the newest one")
  public void everyRecordOfTheTaskIsClosed() {

    final var now = java.time.Instant.now();
    transactionTemplate.executeWithoutResult(status -> {
      deliveryLog
          .record(
              new TaskDelivery("handed-out", "test-adapter", "test-module", "TestProcess", "4711", null, "awaitCompletion", null, "task-9", "COMPLETION_PENDING", null, null, now
                  .minusSeconds(60), null));
      deliveryLog
          .record(
              new TaskDelivery("handed-out-again", "test-adapter", "test-module", "TestProcess", "4711", null, "awaitCompletion", null, "task-9", "COMPLETION_PENDING", null, null, now, null));
    });

    assertEquals(
        2,
        deliveryLog.openTasksOfAggregate("test-module", "TestProcess", "4711").size(),
        "two records name the same task");
    assertEquals(
        2,
        deliveryLog.markTaskClosed("test-module", "TestProcess", "4711", "task-9"),
        "one call closes both - a row left open would keep the task alive");
    assertTrue(deliveryLog.openTasksOfAggregate("test-module", "TestProcess", "4711").isEmpty());

  }

  @Test
  @DisplayName("The open tasks of one aggregate come oldest first, and a closed one is gone")
  public void theOpenTasksOfAnAggregateAreAnswered() {

    final var now = java.time.Instant.now();
    transactionTemplate.executeWithoutResult(status -> {
      deliveryLog
          .record(
              new TaskDelivery("open-2", "test-adapter", "test-module", "TestProcess", "4711", null, "awaitCompletion", null, "task-2", "COMPLETION_PENDING", null, null, now, null));
      deliveryLog
          .record(
              new TaskDelivery("open-1", "test-adapter", "test-module", "TestProcess", "4711", null, "awaitCompletion", null, "task-1", "COMPLETION_PENDING", null, null, now
                  .minusSeconds(600), null));
      // another aggregate, and a delivery which left no task open
      deliveryLog
          .record(
              new TaskDelivery("other", "test-adapter", "test-module", "TestProcess", "4712", null, "awaitCompletion", null, "task-3", "COMPLETION_PENDING", null, null, now, null));
      deliveryLog
          .record(
              new TaskDelivery("done", "test-adapter", "test-module", "TestProcess", "4711", null, "awaitCompletion", null, "task-4", "COMPLETED", null, null, now, null));
    });

    assertEquals(
        java.util.List.of("task-1", "task-2"),
        deliveryLog
            .openTasksOfAggregate("test-module", "TestProcess", "4711")
            .stream()
            .map(TaskDelivery::taskId)
            .toList());

    deliveryLog.markTaskClosed("test-module", "TestProcess", "4711", "task-1");

    assertEquals(
        java.util.List.of("task-2"),
        deliveryLog
            .openTasksOfAggregate("test-module", "TestProcess", "4711")
            .stream()
            .map(TaskDelivery::taskId)
            .toList());
    assertTrue(deliveryLog.openTasksOfAggregate("test-module", "TestProcess", "no-such-aggregate").isEmpty());

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

    return new TaskDelivery(deliveryKey, "test-adapter", workflowModuleId, bpmnProcessId, workflowAggregateId, null, "processTask", null, null, "COMPLETED", null, null, java.time.Instant
        .now(), null);

  }

  @Test
  @DisplayName("An ended workflow releases its records - and only its own")
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
  @DisplayName("A record written after the end of the workflow survives the release")
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
  @DisplayName("No expired record is left once the retention period passed")
  public void noExpiredRecordIsLeft() {

    // two hours old, which is past the hour the log below keeps a record for. The age is
    // written into the record rather than waited for, and it is two hours rather than a
    // moment because the bound of the cleanup is strict while a store's timestamp has
    // millisecond resolution
    transactionTemplate.executeWithoutResult(
        status -> deliveryLog.record(backdated("job-4", java.time.Duration.ofHours(2))));

    // twice on purpose: the second run meets a store the first one already emptied, which
    // is the position a second deleter is in when it gets there first. What is asserted is
    // the state, so the answer holds whoever deleted the record
    final var anHourOfRetention = anHourOfRetention();
    anHourOfRetention.cleanUpExpiredRecords();
    anHourOfRetention.cleanUpExpiredRecords();

    assertTrue(deliveryLog.recordedDelivery("job-4").isEmpty());
    assertEquals(
        0,
        mongoTemplate.getCollection(COLLECTION).countDocuments(),
        "no record past its retention is left");

  }

  /**
   * A log on the collection of this application, with an hour of retention instead of the
   * day the application runs with. It is what lets a test delete a record whose age it
   * chose, while the cleanup running in the background of the application reaches none of
   * them.
   */
  private MongoTaskDeliveryLog anHourOfRetention() {

    return new MongoTaskDeliveryLog(mongoTemplate, COLLECTION, java.time.Duration.ofHours(1));

  }

  /**
   * A record written the given time ago - both of its timestamps, exactly as the insert
   * writes them.
   */
  private TaskDelivery backdated(
      final String deliveryKey,
      final java.time.Duration age) {

    return new TaskDelivery(deliveryKey, "test-adapter", "test-module", "TestProcess", "4711", null, "awaitCompletion", null, null, "COMPLETION_PENDING", null, null, java.time.Instant
        .now()
        .minus(age), null);

  }

  @Test
  @DisplayName("The record of a task which is still redelivered survives the retention")
  public void theRecordOfAnOpenTaskSurvivesTheRetention() {

    final var anHourOfRetention = anHourOfRetention();

    transactionTemplate.executeWithoutResult(status -> {
      deliveryLog.record(backdated("job-open", java.time.Duration.ofHours(2)));
      deliveryLog.record(backdated("job-forgotten", java.time.Duration.ofHours(2)));
    });

    // the BPMS redelivered the open task, which is what the core reports to the store
    anHourOfRetention.stillOpen("job-open");
    anHourOfRetention.cleanUpExpiredRecords();

    assertTrue(
        deliveryLog.recordedDelivery("job-open").isPresent(),
        "a task which is still being redelivered keeps the record answering it");
    assertTrue(
        deliveryLog.recordedDelivery("job-forgotten").isEmpty(),
        "a record nobody has seen for a whole retention expires");

    final var kept = mongoTemplate
        .findById("job-open", io.vanillabp.integration.delivery.TaskDeliveryDocument.class, COLLECTION);
    assertTrue(
        kept.getLastSeenAt().isAfter(kept.getRecordedAt()),
        "the moment it was last seen moved, the moment it was recorded did not");
    assertTrue(
        kept.getRecordedAt().isBefore(java.time.Instant.now().minus(java.time.Duration.ofMinutes(90))),
        "so the age of the open task is still measured from the moment the handler ran");

  }

  @Test
  @DisplayName("More open tasks than one block are refreshed in blocks")
  public void moreOpenTasksThanOneBlockAreRefreshed() {

    final var anHourOfRetention = anHourOfRetention();
    final var tasks = io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches.BLOCK_SIZE + 100;

    transactionTemplate.executeWithoutResult(status -> {
      for (var i = 0; i < tasks; i++) {
        deliveryLog.record(backdated("job-"
            + i, java.time.Duration.ofHours(2)));
      }
    });
    for (var i = 0; i < tasks; i++) {
      anHourOfRetention.stillOpen("job-"
          + i);
    }

    anHourOfRetention.cleanUpExpiredRecords();

    assertEquals(
        tasks,
        mongoTemplate.getCollection(COLLECTION).countDocuments(),
        "every one of them survives");

  }

}
