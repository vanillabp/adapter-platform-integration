package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Reading a record by the task it belongs to, and writing down that the task was closed -
 * what the BPMS election of a task operation asks the store instead of asking a BPMS. The
 * SQL is shared by both platforms, so this is where it is pinned, on H2.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TaskRecordLookupTest {

  private static final String TABLE = "VANILLABP_TASK_DELIVERY";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "4711";

  private final JdbcTaskDeliveryStore testee;

  public TaskRecordLookupTest() {

    final var url = "jdbc:h2:mem:task-lookup-%s;DB_CLOSE_DELAY=-1".formatted(java.util.UUID.randomUUID());
    final JdbcConnectionAccess connections = () -> DriverManager.getConnection(url, "sa", "");
    this.testee = new JdbcTaskDeliveryStore(connections, TABLE);
    testee.createSchemaIfNotExists();

  }

  /**
   * A record as the core writes it while a handler runs.
   *
   * @param deliveryKey The delivery's identity
   * @param taskId The task the record is about, or <code>null</code> for a record written
   *          before the column existed
   * @param outcome What the delivery was answered with
   * @param recordedAt When the handler ran
   */
  private void record(
      final String deliveryKey,
      final String taskId,
      final String outcome,
      final Instant recordedAt) {

    testee
        .record(
            new TaskDelivery(
                deliveryKey, "c8", MODULE, PROCESS, AGGREGATE, "awaitCompletion", taskId, outcome, null, null, recordedAt, null));

  }

  @Test
  @DisplayName("The record of an open task is found by the task the caller names")
  public void theRecordOfAnOpenTaskIsFound() {

    record("open", "job-1", "COMPLETION_PENDING", Instant.now());

    final var found = testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-1").orElseThrow();

    assertEquals("open", found.deliveryKey());
    assertEquals("c8", found.adapterId(), "the adapter which delivered is what the election is after");
    assertEquals("job-1", found.taskId());
    assertNull(found.taskClosedAt(), "a record is born open");

  }

  @Test
  @DisplayName("A record of another workflow, process or module is another question")
  public void aRecordOfAnotherWorkflowIsAnotherQuestion() {

    record("open", "job-1", "COMPLETION_PENDING", Instant.now());

    assertTrue(testee.recordOfTask("other-module", PROCESS, AGGREGATE, "job-1").isEmpty());
    assertTrue(testee.recordOfTask(MODULE, "OtherProcess", AGGREGATE, "job-1").isEmpty());
    assertTrue(testee.recordOfTask(MODULE, PROCESS, "4712", "job-1").isEmpty());
    assertTrue(testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-2").isEmpty());

  }

  @Test
  @DisplayName("A delivery which completed its task leaves no task to elect for")
  public void aCompletedDeliveryIsNoAnswer() {

    record("done", "job-1", "COMPLETED", Instant.now());

    assertTrue(
        testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-1").isEmpty(),
        "only a delivery which LEFT its task open can be completed by the application later");

  }

  @Test
  @DisplayName("A record written before the column existed names no task and answers nothing")
  public void aRecordWithoutATaskAnswersNothing() {

    record("older", null, "COMPLETION_PENDING", Instant.now());

    assertTrue(testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-1").isEmpty());

  }

  @Test
  @DisplayName("Where one task was delivered twice, the most recent record answers")
  public void theMostRecentRecordAnswers() {

    record("first", "job-1", "COMPLETION_PENDING", Instant.now().minusSeconds(600));
    record("second", "job-1", "COMPLETION_PENDING", Instant.now());

    assertEquals("second", testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-1").orElseThrow().deliveryKey());

  }

  @Test
  @DisplayName("A closed task keeps its record and the moment it was closed")
  public void aClosedTaskKeepsTheMomentItWasClosed() {

    record("open", "job-1", "COMPLETION_PENDING", Instant.now());

    assertEquals(1, testee.markTaskClosed(MODULE, PROCESS, AGGREGATE, "job-1"));

    final var closed = testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-1").orElseThrow();
    assertNotNull(closed.taskClosedAt(), "the record says the task is not open any more");
    assertNotNull(closed.recordedAt(), "and it still says when the handler ran");

  }

  @Test
  @DisplayName("A repeated dispatch does not move the moment a task was closed")
  public void aRepeatedDispatchDoesNotMoveTheMoment() {

    record("open", "job-1", "COMPLETION_PENDING", Instant.now());
    testee.markTaskClosed(MODULE, PROCESS, AGGREGATE, "job-1");
    final var closedAt = testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-1").orElseThrow().taskClosedAt();

    assertEquals(
        0,
        testee.markTaskClosed(MODULE, PROCESS, AGGREGATE, "job-1"),
        "the task was closed when it was first closed");
    assertEquals(
        closedAt,
        testee.recordOfTask(MODULE, PROCESS, AGGREGATE, "job-1").orElseThrow().taskClosedAt());

  }

  @Test
  @DisplayName("Marking a task nobody wrote a record for changes nothing")
  public void markingAnUnknownTaskChangesNothing() {

    assertEquals(0, testee.markTaskClosed(MODULE, PROCESS, AGGREGATE, "job-1"));

  }

  @Test
  @DisplayName("A record read by its delivery key carries the task and the moment it was closed")
  public void theRecordReadByItsKeyCarriesBoth() {

    record("open", "job-1", "COMPLETION_PENDING", Instant.now());
    testee.markTaskClosed(MODULE, PROCESS, AGGREGATE, "job-1");

    final var recorded = testee.recordedDelivery("open").orElseThrow();

    assertEquals("job-1", recorded.taskId());
    assertNotNull(recorded.taskClosedAt());
    assertFalse(recorded.deliveryKey().isBlank(), "the key is read back as well, not assumed by the caller");

  }

}
