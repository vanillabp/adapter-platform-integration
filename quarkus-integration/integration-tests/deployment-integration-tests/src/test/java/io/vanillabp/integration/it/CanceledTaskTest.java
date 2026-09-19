package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.test.delivery.DeliveryAggregate;
import io.vanillabp.integration.test.delivery.DeliveryAggregatePersistence;
import io.vanillabp.integration.test.delivery.DeliveryProcessWiringSource;
import io.vanillabp.integration.test.delivery.DeliveryWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskEvent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test on Quarkus, with the default JDBC-based delivery log: a task the BPMS
 * cancels leaves no open record behind.
 * <p>
 * The record the task left open is stamped by the cancelling delivery itself, and that
 * delivery writes no second OPEN record although the method carries a
 * <code>&#64;TaskId</code> parameter. A method which does not subscribe to the
 * cancellation is the case which must not change: its record stays open, because nothing
 * told it the task is over.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CanceledTaskTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("canceled-task/application.yaml", "application.yaml")
          .addClass(DeliveryAggregate.class)
          .addClass(DeliveryAggregatePersistence.class)
          .addClass(DeliveryWorkflowService.class)
          .addClass(DeliveryProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/DeliveryProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  DeliveryAggregatePersistence persistence;

  @Inject
  DataSource dataSource;

  @Inject
  JdbcTaskDeliveryLog deliveryLog;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> ADAPTER.equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  /**
   * One delivery of one task. The delivery id carries the event as well, the way an
   * adapter which activates a job per event builds it.
   */
  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String taskId,
      final TaskEvent.Event event) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getTaskId() {
        return taskId;
      }

      @Override
      public String getDeliveryId() {
        return taskId;
      }

      @Override
      public TaskEvent.Event getTaskEvent() {
        return event;
      }

      @Override
      public String getWorkflowId() {
        return "workflow-of-"
            + aggregateId;
      }

    };

  }

  private int countOf(
      final String sql,
      final String aggregateId) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement(sql.formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setString(1, aggregateId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }

  }

  private int recordCount(
      final String aggregateId) throws SQLException {

    return countOf("SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?", aggregateId);

  }

  private int openRecordCount(
      final String aggregateId) throws SQLException {

    return countOf(
        """
            SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ? AND OUTCOME = 'COMPLETION_PENDING' \
            AND TASK_CLOSED_AT IS NULL""",
        aggregateId);

  }

  private int recordsWithoutAClosingMoment(
      final String aggregateId) throws SQLException {

    return countOf("SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ? AND TASK_CLOSED_AT IS NULL", aggregateId);

  }

  @Test
  @DisplayName("A task the BPMS cancels leaves no open record, and a method not asking for the event keeps its own")
  public void aCancellationClosesTheRecordOfItsTask() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    // (a) a user task handed to the application, which leaves it open
    persistence.store("4711");
    final var created = dummyAdapter
        .invokeTask(MODULE, PROCESS, delivery("cancelableTask", "4711", "task-1", TaskEvent.Event.CREATED));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, created.kind());
    assertEquals(1, openRecordCount("4711"));

    // (b) the BPMS takes the task away and says so
    final var canceled = dummyAdapter
        .invokeTask(MODULE, PROCESS, delivery("cancelableTask", "4711", "task-1", TaskEvent.Event.CANCELED));
    assertEquals(
        WorkflowTaskOutcome.Kind.COMPLETED,
        canceled.kind(),
        "a canceled task is over, whatever the @TaskId parameter of the method promised");
    assertEquals(
        "task-1",
        persistence.get("4711").getCanceledTasks(),
        "the method asked for the event, so it was called with it");

    // what the table says afterwards: no open record left, and every record naming that
    // task carries the moment it was closed. Two rows, one per delivery: the cancellation
    // keeps a record of its own so a BPMS repeating it does not run the method again
    assertEquals(0, openRecordCount("4711"), "the record the task left open is closed by the cancelling delivery");
    assertEquals(2, recordCount("4711"), "one record per delivery");
    assertEquals(0, recordsWithoutAClosingMoment("4711"), "and both of them carry the moment the task was closed");
    assertTrue(
        deliveryLog.openTasksOfAggregate(MODULE, PROCESS, "4711").isEmpty(),
        "so nothing reads that workflow as waiting for a task any more");

    // (c) a method which never asked for the event: the delivery is skipped and the
    // record it left open stays exactly as it was
    persistence.store("4712");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4712", "task-2", TaskEvent.Event.CREATED));
    assertEquals(1, openRecordCount("4712"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4712", "task-2", TaskEvent.Event.CANCELED));
    assertEquals(1, recordCount("4712"), "a delivery nobody subscribed to is skipped before anything is written");
    assertEquals(
        1,
        openRecordCount("4712"),
        "so the record of that task stays open, and the next wake-up is what closes it");

  }

  /**
   * The second record of one task is what {@code markTaskClosed} has to close as well: a
   * task delivered twice under two delivery ids leaves two rows naming the same task, and
   * a row left open keeps the task alive for everything which reads the open work.
   */
  @Test
  @DisplayName("Every record naming the task is closed, not only the newest one")
  public void everyRecordOfTheTaskIsClosed() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4713");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4713", "task-3", TaskEvent.Event.CREATED));
    dummyAdapter
        .invokeTask(
            MODULE,
            PROCESS,
            new TaskInvocationContext() {

              @Override
              public String getAdapterId() {
                return ADAPTER;
              }

              @Override
              public String getTaskDefinition() {
                return "awaitCompletion";
              }

              @Override
              public String getWorkflowAggregateId() {
                return "4713";
              }

              @Override
              public String getTaskId() {
                return "task-3";
              }

              @Override
              public String getDeliveryId() {
                return "another-job-of-task-3";
              }

            });
    assertEquals(2, openRecordCount("4713"), "two records name the same task");

    assertEquals(
        2,
        deliveryLog.markTaskClosed(MODULE, PROCESS, "4713", "task-3"),
        "both of them are closed by one call, and the count says how many it closed");
    assertEquals(0, openRecordCount("4713"));
    assertEquals(
        0,
        deliveryLog.markTaskClosed(MODULE, PROCESS, "4713", "task-3"),
        "a second call finds nothing left to close, which is what lets a caller claim a task");

  }

}
