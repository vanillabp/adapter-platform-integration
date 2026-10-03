package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.spi.DeliveryRecordKind;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.WorkflowStartKey;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The row about the start of a workflow, and the four questions about open work which must not
 * count it. That is where it otherwise goes wrong without a sound: a row without a task would be a
 * phantom task in each of those answers, and the core reads them when a workflow wakes up.
 * <p>
 * The SQL is shared by both platforms, so this is where it is pinned, on H2. The log in front of it
 * is the interface itself, because the read of the start row is a default method and every store
 * answers it without code of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AStartIsNoOpenWorkTest {

  private static final String TABLE = "VANILLABP_TASK_DELIVERY";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "4711";

  private static final String ADAPTER = "c8";

  private final JdbcTaskDeliveryStore store;

  /**
   * The store behind the interface: a log which hands every call on, so the default methods of
   * {@link TaskDeliveryLog} are the ones under test.
   */
  private final TaskDeliveryLog testee;

  public AStartIsNoOpenWorkTest() {

    final var url = "jdbc:h2:mem:a-start-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
    final JdbcConnectionAccess connections = () -> DriverManager.getConnection(url, "sa", "");
    this.store = new JdbcTaskDeliveryStore(connections, TABLE);
    store.createSchemaIfNotExists();
    this.testee = new TaskDeliveryLog() {

      @Override
      public java.util.Optional<TaskDelivery> recordedDelivery(
          final String deliveryKey) {

        return store.recordedDelivery(deliveryKey);

      }

      @Override
      public boolean record(
          final TaskDelivery delivery) {

        return store.record(delivery);

      }

    };

  }

  private void givenAnOpenTask(
      final String deliveryKey,
      final String taskId,
      final String workflowId) {

    givenAnOpenTask(deliveryKey, taskId, workflowId, Instant.now());

  }

  private void givenAnOpenTask(
      final String deliveryKey,
      final String taskId,
      final String workflowId,
      final Instant recordedAt) {

    store
        .record(
            new TaskDelivery(
                deliveryKey, ADAPTER, MODULE, PROCESS, AGGREGATE, workflowId, "awaitSignature", null, taskId, "COMPLETION_PENDING", null, null, recordedAt, null));

  }

  private boolean givenAStartedWorkflow(
      final String workflowId,
      final Instant startedAt) {

    return store
        .record(
            TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, AGGREGATE, workflowId, startedAt));

  }

  @Test
  @DisplayName("The open tasks of an aggregate leave the start of its workflow out")
  public void theOpenTasksOfAnAggregateLeaveTheStartOut() {

    givenAStartedWorkflow("instance-4711", Instant.now());

    assertTrue(
        store.openTasksOfAggregate(MODULE, PROCESS, AGGREGATE).isEmpty(),
        "a row without a task is no open task");

    givenAnOpenTask("a", "job-1", "instance-4711");

    assertEquals(
        List.of("job-1"),
        store
            .openTasksOfAggregate(MODULE, PROCESS, AGGREGATE)
            .stream()
            .map(TaskDelivery::taskId)
            .toList());

  }

  @Test
  @DisplayName("The open tasks of one workflow of the BPMS leave the start of that workflow out")
  public void theOpenTasksOfAWorkflowLeaveTheStartOut() {

    // the start row carries a workflow id, which is exactly what this read is keyed by - so it
    // would be in this answer of all four without the filter
    givenAStartedWorkflow("instance-4711", Instant.now());

    assertTrue(store.openTasksOfWorkflow(MODULE, "instance-4711").isEmpty());

    givenAnOpenTask("a", "job-1", "instance-4711");

    assertEquals(1, store.openTasksOfWorkflow(MODULE, "instance-4711").size());

  }

  @Test
  @DisplayName("The adapter ids of open tasks are not reported for the start of a workflow")
  public void theAdapterIdsOfOpenTasksLeaveTheStartOut() {

    givenAStartedWorkflow("instance-4711", Instant.now());

    assertTrue(
        store.adapterIdsOfOpenTasks(MODULE, PROCESS).isEmpty(),
        "an adapter which started a workflow holds no open task because of it");

    givenAnOpenTask("a", "job-1", "instance-4711");

    assertEquals(java.util.Set.of(ADAPTER), store.adapterIdsOfOpenTasks(MODULE, PROCESS));

  }

  @Test
  @DisplayName("A store holding only the start of a workflow holds no open record")
  public void aStartIsNoOpenRecord() {

    givenAStartedWorkflow("instance-4711", Instant.now());

    assertFalse(
        store.hasOpenRecords(MODULE, PROCESS),
        """
            this answer decides whether the startup reports open tasks nobody remembers - a start \
            row answering it 'yes' would silence that report for every upgrading application""");

    givenAnOpenTask("a", "job-1", "instance-4711");

    assertTrue(store.hasOpenRecords(MODULE, PROCESS));

  }

  @Test
  @DisplayName("The start of a workflow is read back by the aggregate it belongs to")
  public void theStartIsReadBackByItsAggregate() {

    givenAStartedWorkflow("instance-4711", Instant.now());

    final var start = testee
        .workflowStartOf(MODULE, PROCESS, AGGREGATE)
        .orElseThrow();

    assertEquals("instance-4711", start.workflowId());
    assertEquals(ADAPTER, start.adapterId());
    assertEquals(DeliveryRecordKind.WORKFLOW_START.name(), start.recordKind());
    assertNull(start.taskId(), "a start is about no task");
    assertNull(start.outcome(), "a start reports no outcome");
    assertEquals(
        WorkflowStartKey.of(MODULE, PROCESS, AGGREGATE),
        start.deliveryKey(),
        "the key is derived, which is what makes the read a lookup");

  }

  @Test
  @DisplayName("An aggregate nothing was started for has no start to read")
  public void anAggregateWithoutAStartAnswersNothing() {

    givenAnOpenTask("a", "job-1", "instance-4711");

    assertTrue(testee.workflowStartOf(MODULE, PROCESS, "4712").isEmpty());
    assertTrue(
        testee.workflowStartOf(MODULE, PROCESS, AGGREGATE).isEmpty(),
        "a delivery of that aggregate is no start of it");

  }

  @Test
  @DisplayName("A start written twice writes nothing the second time")
  public void aRepeatedStartWritesNothing() {

    assertTrue(givenAStartedWorkflow("instance-4711", Instant.now()));

    assertFalse(
        givenAStartedWorkflow("instance-4711", Instant.now()),
        "an outbox entry dispatched twice must not leave a second row behind");
    assertEquals(
        "instance-4711",
        testee
            .workflowStartOf(MODULE, PROCESS, AGGREGATE)
            .orElseThrow()
            .workflowId(),
        "the row which stands is the one the first start wrote");

  }

  @Test
  @DisplayName("The release of an ended workflow keeps the start, because changes keep arriving")
  public void theReleaseOfAnEndedWorkflowKeepsTheStart() {

    givenAStartedWorkflow("instance-4711", Instant.now().minusSeconds(60));
    givenAnOpenTask("a", "job-1", "instance-4711");

    assertEquals(1, store.deleteRecordsOf(MODULE, PROCESS, AGGREGATE, Instant.now()));

    assertTrue(
        testee.workflowStartOf(MODULE, PROCESS, AGGREGATE).isPresent(),
        "the id of the workflow is what a change reported after the end needs");

  }

  @Test
  @DisplayName("The retention of the deliveries does not take the start, its own period does")
  public void theTwoPeriodsAreTwoPeriods() {

    givenAStartedWorkflow("instance-4711", Instant.now().minus(Duration.ofDays(10)));
    givenAnOpenTask("a", "job-1", "instance-4711", Instant.now().minus(Duration.ofDays(10)));

    assertEquals(
        1,
        store.deleteExpired(Duration.ofDays(7)),
        "the open task expired and the start did not, although both are older than seven days");
    assertTrue(testee.workflowStartOf(MODULE, PROCESS, AGGREGATE).isPresent());

    assertEquals(0, store.deleteExpiredWorkflowStarts(Duration.ofDays(30), null));
    assertTrue(testee.workflowStartOf(MODULE, PROCESS, AGGREGATE).isPresent());

    assertEquals(1, store.deleteExpiredWorkflowStarts(Duration.ofDays(7), null));
    assertTrue(testee.workflowStartOf(MODULE, PROCESS, AGGREGATE).isEmpty());

  }

  @Test
  @DisplayName("A sieve which keeps a row keeps it, and one which lets it go deletes it")
  public void theSieveDecidesPastThePeriod() {

    givenAStartedWorkflow("instance-4711", Instant.now().minus(Duration.ofDays(40)));

    assertEquals(
        0,
        store.deleteExpiredWorkflowStarts(Duration.ofDays(30), row -> false),
        "an aggregate which is still there keeps the row past the period");
    assertEquals(
        0,
        store.deleteExpiredWorkflowStarts(Duration.ofDays(30), row -> null),
        "a sieve which cannot say keeps the row rather than deleting it on a guess");

    assertEquals(1, store.deleteExpiredWorkflowStarts(Duration.ofDays(30), row -> {
      assertEquals(MODULE, row.workflowModuleId());
      assertEquals(PROCESS, row.bpmnProcessId());
      assertEquals(AGGREGATE, row.workflowAggregateId());
      return true;
    }));
    assertTrue(testee.workflowStartOf(MODULE, PROCESS, AGGREGATE).isEmpty());

  }

  @Test
  @DisplayName("A row which says nothing about its kind is a task delivery")
  public void aRowWithoutAKindIsADelivery() {

    // every row of a table which existed before the column did, filled by the ALTER of the
    // changelog and by the default of the DDL
    givenAnOpenTask("a", "job-1", "instance-4711");

    final var delivery = store
        .recordedDelivery("a")
        .orElseThrow();

    assertSame(DeliveryRecordKind.TASK_DELIVERY, DeliveryRecordKind.of(delivery.recordKind()));
    assertSame(DeliveryRecordKind.TASK_DELIVERY, DeliveryRecordKind.of(null));
    assertNull(DeliveryRecordKind.of("SOMETHING_A_NEWER_VERSION_WRITES"));

  }

  @Test
  @DisplayName("No delivery can fall on the key of a start")
  public void noDeliveryCanFallOnTheKeyOfAStart() {

    final var startKey = WorkflowStartKey.of(MODULE, PROCESS, AGGREGATE);

    assertTrue(startKey.contains(WorkflowStartKey.MARKER));
    assertEquals(
        io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryKey.MAX_LENGTH,
        WorkflowStartKey.MAX_LENGTH,
        "both keys live in the same column, so they are bounded by the same number");

  }

}
