package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The open tasks of ONE workflow of the BPMS, which is what the core reads on every
 * wake-up to see what else it still believes is open there. The SQL is shared by both
 * platforms, so this is where it is pinned, on H2.
 * <p>
 * The last case is the reason the read exists at all: it reads an index over
 * <code>WORKFLOW_ID</code> and not the table, so its cost follows what one workflow has
 * open rather than what the whole installation has.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenTasksOfWorkflowTest {

  private static final String TABLE = "VANILLABP_TASK_DELIVERY";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String WORKFLOW = "2251799813685249";

  private final String url;

  private final JdbcTaskDeliveryStore testee;

  public OpenTasksOfWorkflowTest() {

    this.url = "jdbc:h2:mem:open-tasks-of-workflow-%s;DB_CLOSE_DELAY=-1".formatted(java.util.UUID.randomUUID());
    final JdbcConnectionAccess connections = () -> DriverManager.getConnection(url, "sa", "");
    this.testee = new JdbcTaskDeliveryStore(connections, TABLE);
    testee.createSchemaIfNotExists();

  }

  private void record(
      final String deliveryKey,
      final String bpmnProcessId,
      final String workflowId,
      final String taskId,
      final String outcome,
      final Instant recordedAt) {

    testee
        .record(
            new TaskDelivery(deliveryKey, "c8", MODULE, bpmnProcessId, "4711", workflowId, "awaitSignature", null, taskId, outcome, null, null, recordedAt, null));

  }

  private List<String> openTaskIds() {

    return testee
        .openTasksOfWorkflow(MODULE, WORKFLOW)
        .stream()
        .map(TaskDelivery::taskId)
        .toList();

  }

  @Test
  @DisplayName("The open tasks of one workflow come oldest first, which is the order the probes follow")
  public void openTasksComeOldestFirst() {

    final var now = Instant.now();
    record("b", PROCESS, WORKFLOW, "job-2", "COMPLETION_PENDING", now);
    record("a", PROCESS, WORKFLOW, "job-1", "COMPLETION_PENDING", now.minusSeconds(600));

    assertEquals(List.of("job-1", "job-2"), openTaskIds());

  }

  @Test
  @DisplayName("A task which is over is not open any more, whichever of the two closed it")
  public void aTaskWhichIsOverIsGone() {

    record("a", PROCESS, WORKFLOW, "job-1", "COMPLETION_PENDING", Instant.now());
    record("b", PROCESS, WORKFLOW, "job-2", "COMPLETION_PENDING", Instant.now());
    record("c", PROCESS, WORKFLOW, "job-3", "COMPLETED", Instant.now());

    assertEquals(1, testee.markTaskClosed(MODULE, PROCESS, "4711", "job-1"));

    assertEquals(List.of("job-2"), openTaskIds());

  }

  /**
   * A task a called process handed out carries the secondary BPMN process id while
   * belonging to the same workflow of the BPMS. That is why the BPMN process is not part
   * of the question: narrowing by it would drop exactly those tasks.
   */
  @Test
  @DisplayName("A task of a called process belongs to the same workflow")
  public void aTaskOfACalledProcessIsPartOfTheAnswer() {

    final var now = Instant.now();
    record("a", PROCESS, WORKFLOW, "job-1", "COMPLETION_PENDING", now.minusSeconds(600));
    record("b", "CalledProcess", WORKFLOW, "job-2", "COMPLETION_PENDING", now);

    assertEquals(List.of("job-1", "job-2"), openTaskIds());

  }

  @Test
  @DisplayName("Another workflow and another module are other questions")
  public void anotherWorkflowIsAnotherQuestion() {

    record("a", PROCESS, WORKFLOW, "job-1", "COMPLETION_PENDING", Instant.now());
    record("b", PROCESS, "another-workflow", "job-2", "COMPLETION_PENDING", Instant.now());

    assertEquals(List.of("job-1"), openTaskIds());
    assertTrue(testee.openTasksOfWorkflow("other-module", WORKFLOW).isEmpty());
    assertTrue(testee.openTasksOfWorkflow(MODULE, "no-such-workflow").isEmpty());

  }

  /**
   * The one limit of this read, and the reason its javadoc names it: an adapter which
   * reports no workflow leaves the column empty, and such a record can never be found by
   * a workflow.
   */
  @Test
  @DisplayName("A record whose adapter named no workflow is invisible to this read")
  public void aRecordWithoutAWorkflowIsInvisible() {

    record("a", PROCESS, null, "job-1", "COMPLETION_PENDING", Instant.now());

    assertTrue(testee.openTasksOfWorkflow(MODULE, WORKFLOW).isEmpty());
    assertEquals(
        1,
        testee.openTasksOfAggregate(MODULE, PROCESS, "4711").size(),
        "the read by aggregate still finds it - only this one cannot");

  }

  /**
   * What the read costs: the index over WORKFLOW_ID and not a walk over everything which
   * is open in the installation. Read from the plan the database itself prints, so a
   * statement which stops using the index fails here rather than in an installation with
   * many open tasks.
   * <p>
   * The rows are needed for the question to be interesting at all. A planner decides
   * between the index over WORKFLOW_ID and the index over what "open" means, and with a
   * handful of rows either is as good as the other - a thousand open tasks spread over
   * five hundred workflows is what makes the difference visible, which is the shape the
   * measurement in the module's README used as well.
   */
  @Test
  @DisplayName("The read follows the index over WORKFLOW_ID rather than everything which is open")
  public void theReadFollowsTheIndex() throws SQLException {

    final var now = Instant.now();
    for (var workflow = 0; workflow < 500; workflow++) {
      for (var task = 0; task < 2; task++) {
        record(
            "key-%d-%d".formatted(workflow, task),
            PROCESS,
            "workflow-%d".formatted(workflow),
            "job-%d-%d".formatted(workflow, task),
            "COMPLETION_PENDING",
            now);
      }
    }
    record("a", PROCESS, WORKFLOW, "job-1", "COMPLETION_PENDING", now);
    analyze();

    final var plan = explainTheRead();

    assertTrue(
        plan.contains(TABLE
            + "_WORKFLOW"),
        () -> "the read has to follow the index over WORKFLOW_ID, and the plan says: "
            + plan);
    assertEquals(List.of("job-1"), openTaskIds(), "and it answers what the other cases expect");

  }

  /**
   * Lets the database count what is in the table, so its planner chooses by how selective
   * a column is instead of by how many conditions an index matches.
   */
  private void analyze() throws SQLException {

    try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
      statement.execute("ANALYZE");
    }

  }

  private String explainTheRead() throws SQLException {

    try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection
        .prepareStatement(
            """
                EXPLAIN SELECT DELIVERY_KEY FROM %s \
                WHERE WORKFLOW_MODULE_ID = ? AND WORKFLOW_ID = ? \
                AND OUTCOME = ? AND TASK_CLOSED_AT IS NULL \
                ORDER BY RECORDED_AT ASC"""
                .formatted(TABLE))) {
      statement.setString(1, MODULE);
      statement.setString(2, WORKFLOW);
      statement.setString(3, "COMPLETION_PENDING");
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }

  }

}
