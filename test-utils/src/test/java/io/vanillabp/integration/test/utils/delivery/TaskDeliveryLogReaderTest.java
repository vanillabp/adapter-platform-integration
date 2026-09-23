package io.vanillabp.integration.test.utils.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The reader against a database in memory.
 * <p>
 * The table is built here, in the shape the platform's JDBC store builds it, and it
 * carries a name of this test. Which name the platform uses is not this test's question:
 * the reader takes it from the class which owns the table, and that class is not on the
 * classpath of this module.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TaskDeliveryLogReaderTest {

  private static final String LOG_OF_THIS_TEST = "A_DELIVERY_LOG";

  private static final String ADAPTER = "the-adapter";

  private static final String WORKFLOW_MODULE = "a-module";

  private static final String BPMN_PROCESS = "AProcess";

  private static final String AGGREGATE = "4711";

  private static final String TASK_DEFINITION = "doSomething";

  private static final String COMPLETION_PENDING = "COMPLETION_PENDING";

  private static final Instant A_MOMENT = Instant.parse("2026-09-23T08:00:00Z");

  private DataSource dataSource;

  @BeforeEach
  void aDatabaseOfItsOwn() throws Exception {

    // the log is not emptied between test methods, so every method gets a database
    // nobody else writes into
    final var database = new JdbcDataSource();
    database.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID()));
    dataSource = database;
    givenADeliveryLog();

  }

  @Test
  @DisplayName("The record of a delivery says what the log wrote down about it")
  public void theRecordSaysWhatTheLogWroteDown() throws Exception {

    givenADelivery("a-key", "the-task", "instance-4711", "TheTask", A_MOMENT, null);

    final var deliveries = readerOfThisTest().deliveriesOfTask("the-task");

    assertEquals(1, deliveries.size());
    final var delivery = deliveries.getFirst();
    assertEquals("the-task", delivery.taskId());
    assertEquals(ADAPTER, delivery.adapterId());
    assertEquals(WORKFLOW_MODULE, delivery.workflowModuleId());
    assertEquals(BPMN_PROCESS, delivery.bpmnProcessId());
    assertEquals(AGGREGATE, delivery.aggregateId());
    assertEquals("instance-4711", delivery.workflowId());
    assertEquals(TASK_DEFINITION, delivery.taskDefinition());
    assertEquals("TheTask", delivery.bpmnElementId());
    assertEquals(COMPLETION_PENDING, delivery.outcome());
    assertFalse(delivery.taskWasClosed(), "nothing closed this task yet");

  }

  @Test
  @DisplayName("A task nothing was delivered for has no record")
  public void aTaskNothingWasDeliveredForHasNoRecord() throws Exception {

    givenADelivery("a-key", "the-task", "instance-4711", "TheTask", A_MOMENT, null);

    assertEquals(List.of(), readerOfThisTest().deliveriesOfTask("another-task"));

  }

  @Test
  @DisplayName("A task carrying two records reports both, the newest first")
  public void aTaskCarryingTwoRecordsReportsBoth() throws Exception {

    // one task, delivered twice: the BPMS handed it out again after its first turn was
    // cancelled, and the second turn ran at another element of the model
    givenADelivery("the-older-key", "the-task", "instance-4711", "TheFirstTurn", A_MOMENT, null);
    givenADelivery("the-newer-key", "the-task", "instance-4711", "TheSecondTurn", A_MOMENT.plusSeconds(60), null);

    final var deliveries = readerOfThisTest().deliveriesOfTask("the-task");

    assertEquals(2, deliveries.size());
    assertEquals("TheSecondTurn", deliveries.getFirst().bpmnElementId(), "the newest record answers first");
    assertEquals(2, readerOfThisTest().deliveries().size(), "and the log holds nothing else");

  }

  @Test
  @DisplayName("A task whose completion reached the BPMS is reported as closed")
  public void aTaskWhoseCompletionReachedTheBpmsIsClosed() throws Exception {

    givenADelivery("a-key", "the-task", "instance-4711", "TheTask", A_MOMENT, A_MOMENT.plusSeconds(5));

    assertTrue(readerOfThisTest().deliveriesOfTask("the-task").getFirst().taskWasClosed());

  }

  @Test
  @DisplayName("Removing everything leaves the log empty for the next test")
  public void removingEverythingLeavesTheLogEmpty() throws Exception {

    givenADelivery("a-key", "the-task", "instance-4711", "TheTask", A_MOMENT, null);
    givenADelivery("another-key", "another-task", "instance-4712", "TheOtherTask", A_MOMENT, null);

    final var reader = readerOfThisTest();
    reader.removeAllDeliveries();

    assertEquals(List.of(), reader.deliveries());

  }

  @Test
  @DisplayName("A reader is built without the class which names the table, and only a question needs it")
  public void aReaderIsBuiltWithoutTheClassWhichNamesTheTable() {

    // the platform class owning the table is not on the classpath of this module, which
    // is what makes the point: building the reader asks for no name at all
    final var reader = TaskDeliveryLogReader.of(dataSource);

    final var noSuchClass = assertThrows(IllegalStateException.class, reader::deliveryLogTableName);

    assertTrue(noSuchClass.getMessage().contains("JdbcTaskDeliveryStore"), noSuchClass.getMessage());

  }

  private TaskDeliveryLogReader readerOfThisTest() {

    return new TaskDeliveryLogReader(dataSource, () -> LOG_OF_THIS_TEST);

  }

  private void givenADeliveryLog() throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement
          .executeUpdate(
              """
                  CREATE TABLE %s (\
                  DELIVERY_KEY VARCHAR(512) PRIMARY KEY, \
                  ADAPTER_ID VARCHAR(255), \
                  WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
                  BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
                  AGGREGATE_ID VARCHAR(1024), \
                  TASK_DEFINITION VARCHAR(255), \
                  TASK_ID VARCHAR(255), \
                  OUTCOME VARCHAR(32) NOT NULL, \
                  BPMN_ERROR_CODE VARCHAR(255), \
                  BPMN_ERROR_NAME VARCHAR(255), \
                  RECORDED_AT TIMESTAMP NOT NULL, \
                  LAST_SEEN_AT TIMESTAMP NOT NULL, \
                  TASK_CLOSED_AT TIMESTAMP, \
                  BPMN_ELEMENT_ID VARCHAR(255), \
                  WORKFLOW_ID VARCHAR(255))"""
                  .formatted(LOG_OF_THIS_TEST));
    }

  }

  /**
   * Writes one record, the way the store of the platform writes it.
   *
   * @param deliveryKey What the record is keyed by
   * @param taskId The task as the BPMS names it
   * @param workflowId The workflow of the BPMS
   * @param bpmnElementId The element which handed the task out
   * @param recordedAt The moment the handler ran
   * @param taskClosedAt The moment the completion reached the BPMS, <code>null</code>
   *          while the task is open
   */
  private void givenADelivery(
      final String deliveryKey,
      final String taskId,
      final String workflowId,
      final String bpmnElementId,
      final Instant recordedAt,
      final Instant taskClosedAt) throws SQLException {

    try (var connection = dataSource.getConnection(); var insert = connection
        .prepareStatement(
            """
                INSERT INTO %s (DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, \
                AGGREGATE_ID, TASK_DEFINITION, TASK_ID, OUTCOME, RECORDED_AT, LAST_SEEN_AT, \
                TASK_CLOSED_AT, BPMN_ELEMENT_ID, WORKFLOW_ID) \
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                .formatted(LOG_OF_THIS_TEST))) {
      insert.setString(1, deliveryKey);
      insert.setString(2, ADAPTER);
      insert.setString(3, WORKFLOW_MODULE);
      insert.setString(4, BPMN_PROCESS);
      insert.setString(5, AGGREGATE);
      insert.setString(6, TASK_DEFINITION);
      insert.setString(7, taskId);
      insert.setString(8, COMPLETION_PENDING);
      insert.setTimestamp(9, Timestamp.from(recordedAt));
      insert.setTimestamp(10, Timestamp.from(recordedAt));
      insert.setTimestamp(11, taskClosedAt == null
          ? null
          : Timestamp.from(taskClosedAt));
      insert.setString(12, bpmnElementId);
      insert.setString(13, workflowId);
      insert.executeUpdate();
    }

  }

}
