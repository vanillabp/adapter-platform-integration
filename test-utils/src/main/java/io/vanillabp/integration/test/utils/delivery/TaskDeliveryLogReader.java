package io.vanillabp.integration.test.utils.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.vanillabp.integration.test.utils.ConstantOfAnotherModule;

/**
 * What a test wants to know about the delivery log, read from the database of the
 * application under test.
 * <p>
 * A test asks what the log wrote down for a task, and what one of those records says. It
 * does not ask for columns, and it never writes the table name down: the name comes from
 * the platform class which owns the table, so a rename in the platform is followed in one
 * file instead of in every repository which tests against a BPMS.
 * <p>
 * That name is read when a statement needs it and not when the reader is built. A test
 * which builds the reader in its setup and then asks it nothing must not fail over a name
 * nobody needed.
 * <p>
 * The reader uses a connection of its own, outside the transaction of the test. So it
 * reads what is committed, which is what a test about the log asks about anyway: the
 * record of a delivery becomes visible when the transaction of the handler commits.
 * <p>
 * The relational log is the one this serves. An application whose delivery log is a
 * MongoDB collection reads it through the template it already has.
 */
public final class TaskDeliveryLogReader {

  /**
   * The store which owns the table of the relational delivery log, and the name of that
   * table.
   */
  private static final String DELIVERY_STORE = "io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore";

  /**
   * The columns of a record this reader reports.
   */
  private static final String COLUMNS_OF_A_RECORD = """
      TASK_ID, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, OUTCOME, TASK_CLOSED_AT""";

  /**
   * What the log wrote down about one delivery of one task.
   * <p>
   * A task can carry more than one record: the delivery which handed it to the
   * application and the delivery which reported its cancellation are two, and so are two
   * turns of a task the BPMS handed out twice.
   *
   * @param taskId The task as the BPMS names it, empty where the BPMS named none
   * @param adapterId The adapter which delivered the task
   * @param workflowModuleId The workflow module the task belongs to
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param aggregateId The workflow aggregate the task was delivered for
   * @param workflowId The workflow of the BPMS, empty where the BPMS named none
   * @param taskDefinition What the model calls the task, empty where the BPMN element is
   *          the only name
   * @param bpmnElementId The element of the model which handed the task out
   * @param outcome What the handler of the application reported back
   * @param taskWasClosed Whether the completion of the task has reached the BPMS
   */
  public record Delivery(
                         String taskId,
                         String adapterId,
                         String workflowModuleId,
                         String bpmnProcessId,
                         String aggregateId,
                         String workflowId,
                         String taskDefinition,
                         String bpmnElementId,
                         String outcome,
                         boolean taskWasClosed) {

  }

  private final DataSource dataSource;

  private final Supplier<String> tableName;

  /**
   * @param dataSource The database of the application under test
   * @param tableName The table the log lies in, asked for when a statement needs it
   */
  TaskDeliveryLogReader(
      final DataSource dataSource,
      final Supplier<String> tableName) {

    this.dataSource = dataSource;
    this.tableName = tableName;

  }

  /**
   * The reader for the delivery log of an application, as long as that application did
   * not configure a table name of its own.
   *
   * @param dataSource The database of the application under test
   * @return The reader
   */
  public static TaskDeliveryLogReader of(
      final DataSource dataSource) {

    return new TaskDeliveryLogReader(
        dataSource, () -> ConstantOfAnotherModule.of(TaskDeliveryLogReader.class, DELIVERY_STORE,
            "DEFAULT_TABLE_NAME"));

  }

  /**
   * Names the table this reader reads, so a failing test can say where it looked.
   *
   * @return The table
   */
  public String deliveryLogTableName() {

    return tableName.get();

  }

  /**
   * Every record of the log. A test asking about something this reader has no question
   * for reads them all and picks its own, which a test database can afford: it holds the
   * records of that test and of nothing else.
   *
   * @return The records, the newest first
   */
  public List<Delivery> deliveries() {

    return read("");

  }

  /**
   * What the log wrote down about one task.
   *
   * @param taskId The task as the BPMS names it
   * @return Its records, the newest first, empty where the log holds none
   */
  public List<Delivery> deliveriesOfTask(
      final String taskId) {

    return read(" WHERE TASK_ID = ?", taskId);

  }

  /**
   * Removes every record, which is what a test leaves behind for the next one. The log
   * outlives a test class otherwise: nothing in the platform empties it, and the
   * retention removes a record long after every test is over.
   */
  public void removeAllDeliveries() {

    execute("DELETE FROM %s".formatted(deliveryLogTableName()));

  }

  /**
   * @param where What narrows the read down, empty for every record
   * @param bindings What to bind to the parameters of that condition
   * @return What the log says about the records read
   */
  private List<Delivery> read(
      final String where,
      final String... bindings) {

    final var statement = "SELECT %s FROM %s%s ORDER BY RECORDED_AT DESC"
        .formatted(COLUMNS_OF_A_RECORD, deliveryLogTableName(), where);
    try (var connection = dataSource.getConnection(); var select = connection.prepareStatement(statement)) {
      for (var binding = 0; binding < bindings.length; ++binding) {
        select.setString(binding + 1, bindings[binding]);
      }
      try (var results = select.executeQuery()) {
        final var deliveries = new ArrayList<Delivery>();
        while (results.next()) {
          deliveries.add(deliveryOf(results));
        }
        return deliveries;
      }
    } catch (final SQLException cannotRead) {
      throw new IllegalStateException(
          "Could not read the delivery log '%s'!".formatted(deliveryLogTableName()), cannotRead);
    }

  }

  /**
   * @param results The row read by {@link #read}
   * @return What the row says about the delivery
   */
  private Delivery deliveryOf(
      final ResultSet results) throws SQLException {

    return new Delivery(
        results.getString("TASK_ID"), results.getString("ADAPTER_ID"), results.getString("WORKFLOW_MODULE_ID"), results
            .getString("BPMN_PROCESS_ID"), results.getString("AGGREGATE_ID"), results.getString("WORKFLOW_ID"), results
                .getString("TASK_DEFINITION"), results.getString(
                    "BPMN_ELEMENT_ID"), results.getString("OUTCOME"), results.getTimestamp("TASK_CLOSED_AT") != null);

  }

  /**
   * @param statement The statement to run
   */
  private void execute(
      final String statement) {

    try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(statement)) {
      update.executeUpdate();
    } catch (final SQLException cannotWrite) {
      throw new IllegalStateException(
          "Could not run '%s' on the database of the application under test!".formatted(statement), cannotWrite);
    }

  }

}
