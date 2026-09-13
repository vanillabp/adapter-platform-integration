package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
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
 * The open tasks of one workflow aggregate, which is what an extension asks before it
 * shows what a workflow is waiting for. The SQL is shared by both platforms, so this is
 * where it is pinned, on H2.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenTasksOfAggregateTest {

  private static final String TABLE = "VANILLABP_TASK_DELIVERY";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "4711";

  private final JdbcTaskDeliveryStore testee;

  public OpenTasksOfAggregateTest() {

    final var url = "jdbc:h2:mem:open-tasks-%s;DB_CLOSE_DELAY=-1".formatted(java.util.UUID.randomUUID());
    final JdbcConnectionAccess connections = () -> DriverManager.getConnection(url, "sa", "");
    this.testee = new JdbcTaskDeliveryStore(connections, TABLE);
    testee.createSchemaIfNotExists();

  }

  private void record(
      final String deliveryKey,
      final String aggregateId,
      final String bpmnProcessId,
      final String taskId,
      final String outcome,
      final Instant recordedAt) {

    testee
        .record(
            new TaskDelivery(
                deliveryKey, "c8", MODULE, bpmnProcessId, aggregateId, "awaitSignature", taskId, outcome, null, null, recordedAt, null));

  }

  private List<String> openTaskIds() {

    return testee
        .openTasksOfAggregate(MODULE, PROCESS, AGGREGATE)
        .stream()
        .map(TaskDelivery::taskId)
        .toList();

  }

  @Test
  @DisplayName("The open tasks of an aggregate come oldest first")
  public void openTasksComeOldestFirst() {

    final var now = Instant.now();
    record("b", AGGREGATE, PROCESS, "job-2", "COMPLETION_PENDING", now);
    record("a", AGGREGATE, PROCESS, "job-1", "COMPLETION_PENDING", now.minusSeconds(600));

    assertEquals(List.of("job-1", "job-2"), openTaskIds());

  }

  @Test
  @DisplayName("A task whose completion reached the BPMS is not open any more")
  public void aClosedTaskIsGone() {

    record("a", AGGREGATE, PROCESS, "job-1", "COMPLETION_PENDING", Instant.now());
    record("b", AGGREGATE, PROCESS, "job-2", "COMPLETION_PENDING", Instant.now());

    assertEquals(1, testee.markTaskClosed(MODULE, PROCESS, AGGREGATE, "job-1"));

    assertEquals(List.of("job-2"), openTaskIds());

  }

  @Test
  @DisplayName("A delivery which did not leave a task open is no open task")
  public void anOutcomeWhichClosedTheTaskIsNoOpenTask() {

    record("a", AGGREGATE, PROCESS, "job-1", "COMPLETED", Instant.now());
    record("b", AGGREGATE, PROCESS, "job-2", "BPMN_ERROR", Instant.now());

    assertTrue(openTaskIds().isEmpty());

  }

  @Test
  @DisplayName("Another aggregate, another process and another module are other questions")
  public void anotherAggregateIsAnotherQuestion() {

    record("a", AGGREGATE, PROCESS, "job-1", "COMPLETION_PENDING", Instant.now());
    record("b", "4712", PROCESS, "job-2", "COMPLETION_PENDING", Instant.now());
    record("c", AGGREGATE, "OtherProcess", "job-3", "COMPLETION_PENDING", Instant.now());

    assertEquals(List.of("job-1"), openTaskIds());
    assertTrue(testee.openTasksOfAggregate("other-module", PROCESS, AGGREGATE).isEmpty());

  }

  @Test
  @DisplayName("An aggregate nobody recorded anything for has no open tasks")
  public void anUnknownAggregateHasNoOpenTasks() {

    assertTrue(testee.openTasksOfAggregate(MODULE, PROCESS, "no-such-aggregate").isEmpty());

  }

  @Test
  @DisplayName("The records carry everything a caller needs, not only the task id")
  public void theRecordsCarryTheWholeDelivery() {

    final var recordedAt = Instant.now().minusSeconds(60);
    record("a", AGGREGATE, PROCESS, "job-1", "COMPLETION_PENDING", recordedAt);

    final var open = testee.openTasksOfAggregate(MODULE, PROCESS, AGGREGATE).getFirst();

    assertEquals("a", open.deliveryKey());
    assertEquals("c8", open.adapterId(), "which BPMS holds the task is part of the answer");
    assertEquals("awaitSignature", open.taskDefinition());
    assertEquals(MODULE, open.workflowModuleId());
    assertEquals(PROCESS, open.bpmnProcessId());
    assertEquals(AGGREGATE, open.workflowAggregateId());

  }

  @Test
  @DisplayName("The answer is read-only, so a caller cannot change what the next one reads")
  public void theAnswerIsReadOnly() {

    record("a", AGGREGATE, PROCESS, "job-1", "COMPLETION_PENDING", Instant.now());

    final var open = testee.openTasksOfAggregate(MODULE, PROCESS, AGGREGATE);

    assertThrows(UnsupportedOperationException.class, open::clear);

  }

}
