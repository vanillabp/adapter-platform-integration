package io.vanillabp.integration.adapter.migration.delivery;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.spi.TaskDelivery;
import lombok.extern.slf4j.Slf4j;

/**
 * The SQL behind the JDBC-based {@link io.vanillabp.integration.spi.TaskDeliveryLog}
 * implementations of both platforms: reading, writing and cleaning up the records of
 * processed task deliveries. Everything platform-specific is behind
 * {@link JdbcConnectionAccess} - the connection has to belong to the transaction which
 * persists the workflow aggregate, otherwise the record and the aggregate would not
 * commit together.
 * <p>
 * A row of this table is either a task delivery or the start of a workflow, and
 * <code>RECORD_KIND</code> is what says which. Every question about open work filters on it
 * first, so a start - which carries no task - is in none of those answers, and the retention of
 * the deliveries leaves it alone because it has a period of its own.
 * <p>
 * A record is INSERTed once and never rewritten: the delivery key is the primary key, so
 * two nodes processing the same delivery concurrently end up with one record and the
 * loser learns it from the constraint violation ({@link #record(TaskDelivery)} returns
 * <code>false</code> then, exactly like a duplicate outbox entry). A row of a delivery
 * nobody deduplicates carries a key which belongs to that row alone, so it cannot be the
 * loser of such a race and nothing here treats it differently. Two columns change
 * afterwards and nothing else does: <code>LAST_SEEN_AT</code>, the moment the BPMS last
 * redelivered the task the record answers - what the retention counts from - and
 * <code>TASK_CLOSED_AT</code>, the moment the application's completion of that task reached
 * the BPMS.
 * <p>
 * The DDL is kept portable the same way the Quarkus outbox does it: table existence is
 * checked via JDBC metadata (<code>CREATE TABLE IF NOT EXISTS</code> is not supported
 * by Oracle and SQL Server), the timestamp type is chosen per database and the delivery
 * key is limited to {@value io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryKey#MAX_LENGTH}
 * characters so MySQL's key-length limit (3072 bytes with utf8mb4) is respected - the
 * core hashes longer keys before they ever reach a store.
 * <p>
 * Why a record is written at all and why it carries two timestamps is decision 6 in the
 * repository's DECISIONS.md; where the schema comes from and why the startup check reads the
 * columns is decision 16 in the repository's DECISIONS.md; why the adapter id is a column of its
 * own is
 * decision 17 in the repository's DECISIONS.md; why the record also answers which adapter holds a
 * task is decision 30 in the repository's DECISIONS.md.
 */
@Slf4j
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class JdbcTaskDeliveryStore {

  /**
   * The default name of the table holding the records, used where the application names
   * none (<code>vanillabp.outbox.jdbc.delivery-table</code>).
   */
  public static final String DEFAULT_TABLE_NAME = "VANILLABP_TASK_DELIVERY";

  /**
   * Resolves the configured table name
   * (<code>vanillabp.outbox.jdbc.delivery-table</code>, falling back to
   * {@link #DEFAULT_TABLE_NAME}). Both platforms ask this method instead of reading the
   * key themselves, so an application which renames the table is followed by the store, by
   * the startup check and by the message about two stores in one table.
   *
   * @param properties The outbox configuration, which carries the store settings of the
   *          delivery log
   * @return The table name
   */
  public static String tableName(
      final PhaseTwoOutboxProperties properties) {

    final var table = properties
        .getJdbc()
        .getDeliveryTable();
    return table == null ? DEFAULT_TABLE_NAME : table;

  }

  private static final String SELECT_DELIVERY = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND \
      FROM %s \
      WHERE DELIVERY_KEY = ?""";

  /**
   * The record which left one task open, read once per task operation of the application:
   * the election asks it instead of asking every configured BPMS which of them holds the
   * task. TASK_ID is indexed, so this is a lookup and not a scan; the remaining columns
   * narrow the answer down to the one workflow, because a task id is only unique within its
   * BPMS.
   * <p>
   * Only a record reporting <code>COMPLETION_PENDING</code> qualifies - that is the outcome
   * which leaves a task open, and only such a task can be completed or cancelled later.
   * Ordered by RECORDED_AT so the most recent one answers where a task was delivered more
   * than once (a redelivery of an open task writes no second record, but a task cancelled
   * and created again does).
   */
  private static final String SELECT_RECORD_OF_TASK = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND \
      FROM %s \
      WHERE TASK_ID = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? \
      AND OUTCOME = ? \
      ORDER BY RECORDED_AT DESC""";

  /**
   * How many rows the election reads: the newest record of that task, and nothing behind it.
   * Set on the statement rather than written as a LIMIT clause, for the reason
   * {@link #ROWS_OF_AN_EXISTENCE_QUESTION} spells out.
   */
  private static final int ROWS_OF_THE_NEWEST_RECORD = 1;

  /**
   * The OPEN records of one workflow aggregate: the outcome which left a task to the
   * application, and no moment saying the completion reached the BPMS. Ordered oldest first,
   * which is the order the tasks were handed out in.
   * <p>
   * The index this reads is {@link #INDEX_OF_OPEN_RECORDS}, over the kind of the row and the two
   * columns which say "open". AGGREGATE_ID is not part of it: the column holds up to 1024 characters, and an
   * index over it exceeds the key-length limit of MySQL (3072 bytes with utf8mb4) and of a
   * DB2 database using 4K pages - the same reason the release of an ended workflow ships
   * without one. So the index narrows the read to the tasks which are open right now, whose
   * number follows what the BPMS hands out rather than everything ever recorded, and the
   * three equality columns are matched on those rows. An application with very many
   * concurrently open tasks adds an index over AGGREGATE_ID itself, prefixed the way its
   * database spells it, which this module's README says as well.
   * <p>
   * What that costs was measured on PostgreSQL 16.15 in September 2026, with the table in
   * memory: 0.22 ms at 501 concurrently open records, 0.68 ms at 5001, 5.73 ms at 50001 and
   * 34.30 ms at 500001, against 0.27 ms at every one of those sizes on MongoDB, which indexes
   * the aggregate id itself. PostgreSQL uses this index until the open records are about a
   * fifth of the table and reads the table itself beyond that, and the cost is a straight line
   * through that switch, because either way one pass covers the open records. The history is
   * out of it: the index scan examined the same 501 entries in 0.17 ms whether the table held
   * one million records or two million. The numbers and the reasoning for shipping no index
   * over AGGREGATE_ID are in this module's README.
   */
  private static final String SELECT_OPEN_TASKS_OF_AGGREGATE = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? \
      AND OUTCOME = ? AND TASK_CLOSED_AT IS NULL \
      ORDER BY RECORDED_AT ASC""";

  /**
   * The statement creating the index the open tasks of an aggregate are read through. Two
   * placeholders for the table name, like every other DDL of this class.
   * <p>
   * RECORD_KIND stands FIRST, which is the question asked first: is this row about a task at
   * all. Two equality columns can stand in either order without changing what the database does
   * with them - what has to stand last is TASK_CLOSED_AT, which is read as a range - so the
   * order is the one a reader follows.
   */
  private static final String INDEX_OF_OPEN_RECORDS = "CREATE INDEX %s_OPEN ON %s (RECORD_KIND, OUTCOME, TASK_CLOSED_AT)";

  /**
   * The OPEN records of ONE workflow of the BPMS, read on every wake-up of that workflow.
   * Ordered oldest first, which is the order the tasks were handed out in and the order the
   * probes of a wake-up follow.
   * <p>
   * WORKFLOW_ID carries an index of its own ({@link #INDEX_OF_WORKFLOW}), unlike AGGREGATE_ID:
   * the column is VARCHAR(255) and stays inside the key-length limit of every database this
   * runs on, the same way TASK_ID does. What that buys is measured in this module's README -
   * the read by aggregate grows with the number of tasks open in the whole installation, this
   * one does not.
   * <p>
   * The BPMN process is NOT part of the question. A workflow of the BPMS is one instance, and
   * a task a called process handed out carries the secondary process id while belonging to
   * the same instance - so narrowing by process would drop exactly those.
   */
  private static final String SELECT_OPEN_TASKS_OF_WORKFLOW = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND WORKFLOW_ID = ? \
      AND OUTCOME = ? AND TASK_CLOSED_AT IS NULL \
      ORDER BY RECORDED_AT ASC""";

  /**
   * The statement creating the index the open tasks of one workflow are read through. Two
   * placeholders for the table name, like every other DDL of this class.
   */
  private static final String INDEX_OF_WORKFLOW = "CREATE INDEX %s_WORKFLOW ON %s (WORKFLOW_ID)";

  /**
   * The adapter ids the OPEN records of one BPMN process belong to. Asked once
   * per BPMN process at startup, never at runtime, and answered from the same index the
   * cleanup uses; a record written before ADAPTER_ID existed carries none and is skipped.
   */
  private static final String SELECT_ADAPTER_IDS_OF_OPEN_TASKS = """
      SELECT DISTINCT ADAPTER_ID \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND OUTCOME = ? \
      AND ADAPTER_ID IS NOT NULL""";

  /**
   * Whether ANY open record of one BPMN process exists. The question is about existence,
   * so exactly one row is wanted no matter how many there are - see
   * {@link #ROWS_OF_AN_EXISTENCE_QUESTION} for why the row limit is set on the statement
   * rather than written into this text.
   */
  private static final String SELECT_ANY_OPEN_RECORD = """
      SELECT DELIVERY_KEY \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND OUTCOME = ?""";

  /**
   * How many rows a question about EXISTENCE fetches.
   * <p>
   * Reading the first row of a result set is not the same as asking for one row. A driver
   * decides for itself how much it fetches before <code>next()</code> answers, and the
   * PostgreSQL driver reads the WHOLE result set into memory unless the statement says
   * otherwise. An application with a hundred thousand open task deliveries would therefore
   * transfer a hundred thousand keys while booting, to learn whether there is at least
   * one - the growth with the age of the application which decision 19 in the repository's
   * DECISIONS.md forbids of a startup check.
   * <p>
   * {@link java.sql.Statement#setMaxRows(int)} rather than a <code>LIMIT</code> clause: the
   * syntax for it differs per database while the JDBC call does not, and it reaches the
   * server, because the extended query protocol carries the row count in the message which
   * executes the statement.
   */
  private static final int ROWS_OF_AN_EXISTENCE_QUESTION = 1;

  /**
   * The outcome of a delivery which left its task open - the only records the three
   * questions about open tasks are interested in.
   */
  private static final String COMPLETION_PENDING = io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome.Kind.COMPLETION_PENDING
      .name();

  /**
   * The kind of row every question about open work filters on, and the kind the retention of
   * deliveries deletes by age. A row which says nothing about its kind was written before the
   * column existed and is a delivery, which is why the column is NOT NULL with this as its
   * default: an ALTER of an existing table fills it, and from then on the filter is a plain
   * equality.
   */
  private static final String TASK_DELIVERY = io.vanillabp.integration.spi.DeliveryRecordKind.TASK_DELIVERY
      .name();

  /**
   * The kind of row which says where a workflow runs, and the only one the retention of started
   * workflows deletes.
   */
  private static final String WORKFLOW_START = io.vanillabp.integration.spi.DeliveryRecordKind.WORKFLOW_START
      .name();

  /**
   * The kind a row is written under: what the record says, and a delivery where it says nothing.
   *
   * @param delivery The record about to be written
   * @return The text of the kind, never <code>null</code>
   */
  private static String kindOfTheRow(
      final TaskDelivery delivery) {

    // a kind this version does not know is written as a delivery rather than as itself: the
    // questions about open work and the two retentions are equalities on the two kinds, so a third
    // word would put the row in no answer and in no cleanup and leave it in the table for good
    final var kind = io.vanillabp.integration.spi.DeliveryRecordKind.of(delivery.recordKind());
    return kind == null
        ? TASK_DELIVERY
        : kind.name();

  }

  private static final String INSERT_DELIVERY = """
      INSERT INTO %s \
      (DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      LAST_SEEN_AT, TASK_KIND, RECORD_KIND) \
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

  // TASK_CLOSED_AT is not written here: a record is born open, and the moment the
  // application's completion reached the BPMS is the one thing about a task which is known
  // long after the handler ran
  private static final String CLOSE_TASK = """
      UPDATE %s \
      SET TASK_CLOSED_AT = ? \
      WHERE TASK_ID = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? \
      AND TASK_CLOSED_AT IS NULL""";

  // one key per execution instead of an IN list, whose length is capped differently by
  // every database (Oracle at 1000 expressions, SQL Server at about 2100 parameters) -
  // the statement is executed as a JDBC batch, which knows no such limit
  private static final String TOUCH_DELIVERY = """
      UPDATE %s \
      SET LAST_SEEN_AT = ? \
      WHERE DELIVERY_KEY = ?""";

  private static final String DELETE_EXPIRED_DELIVERIES = """
      DELETE FROM %s \
      WHERE RECORD_KIND = ? AND LAST_SEEN_AT < ?""";

  /**
   * The rows about started workflows whose own retention passed. LAST_SEEN_AT and not
   * RECORDED_AT, although the two hold the same moment in such a row: nothing ever moves
   * LAST_SEEN_AT of a start row - only a redelivery of an open task does that, and a start row
   * answers no redelivery - and deleting by the column the AGE index spans is what keeps this an
   * indexed delete rather than a scan.
   */
  private static final String DELETE_EXPIRED_WORKFLOW_STARTS = """
      DELETE FROM %s \
      WHERE RECORD_KIND = ? AND LAST_SEEN_AT < ?""";

  /**
   * The rows about started workflows the sieve is asked about, read instead of deleted where an
   * application asked for the sieve. The row limit of one run is
   * {@link #WORKFLOW_STARTS_SIEVED_PER_RUN}; what is not reached is reached by the next run.
   */
  private static final String SELECT_EXPIRED_WORKFLOW_STARTS = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND \
      FROM %s \
      WHERE RECORD_KIND = ? AND LAST_SEEN_AT < ? \
      ORDER BY LAST_SEEN_AT ASC""";

  /**
   * How many rows one sieved run looks at. The sieve costs one read of the application's own
   * database per row, so a run has an upper bound instead of taking however many rows expired
   * while the application was stopped; the next hourly run continues where this one stopped,
   * because what it kept is kept and what it deleted is gone.
   */
  private static final int WORKFLOW_STARTS_SIEVED_PER_RUN = 1000;

  private static final String DELETE_WORKFLOW_START = """
      DELETE FROM %s \
      WHERE DELIVERY_KEY = ?""";

  /**
   * What a SECOND workflow of one aggregate writes: the row is keyed by the aggregate and the BPMN
   * process, so the insert of such a start finds the row of the workflow which ended before it and
   * the id in it has to be replaced. Bounded to a row which really is a start and whose id really
   * differs, so a start dispatched twice updates nothing.
   * <p>
   * LAST_SEEN_AT moves with it, which is what the period of a start row counts from: the row is
   * about the workflow which runs now, so its age is that workflow's age.
   */
  private static final String REPLACE_WORKFLOW_START = """
      UPDATE %s \
      SET WORKFLOW_ID = ?, ADAPTER_ID = ?, RECORDED_AT = ?, LAST_SEEN_AT = ? \
      WHERE DELIVERY_KEY = ? AND RECORD_KIND = ? \
      AND (WORKFLOW_ID IS NULL OR WORKFLOW_ID <> ?)""";

  private static final String DELETE_DELIVERIES_OF_WORKFLOW = """
      DELETE FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? \
      AND RECORDED_AT < ?""";

  private final JdbcConnectionAccess connectionAccess;

  private final String tableName;

  private final String selectDelivery;

  private final String selectRecordOfTask;

  private final String insertDelivery;

  private final String closeTask;

  private final String touchDelivery;

  private final String deleteExpiredDeliveries;

  private final String deleteExpiredWorkflowStarts;

  private final String selectExpiredWorkflowStarts;

  private final String deleteWorkflowStart;

  private final String replaceWorkflowStart;

  private final String deleteDeliveriesOfWorkflow;

  private final String selectAdapterIdsOfOpenTasks;

  private final String selectAnyOpenRecord;

  private final String selectOpenTasksOfAggregate;

  private final String selectOpenTasksOfWorkflow;

  private final OpenTaskTouches touches;

  /**
   * Built by the platform's delivery log, once per application.
   * <p>
   * The statements are composed here rather than per call, because the table name is the one
   * thing about them which can differ and it is known now. Nothing touches the database yet -
   * the schema is checked when the log starts.
   *
   * @param connectionAccess Where a connection of the running transaction comes from, which
   *          is what makes a record commit together with the workflow aggregate
   * @param tableName The table holding the records, {@link #DEFAULT_TABLE_NAME} unless the
   *          application configured another one
   */
  public JdbcTaskDeliveryStore(
      final JdbcConnectionAccess connectionAccess,
      final String tableName) {

    this.connectionAccess = connectionAccess;
    this.tableName = tableName;
    this.selectDelivery = SELECT_DELIVERY.formatted(tableName);
    this.selectRecordOfTask = SELECT_RECORD_OF_TASK.formatted(tableName);
    this.insertDelivery = INSERT_DELIVERY.formatted(tableName);
    this.closeTask = CLOSE_TASK.formatted(tableName);
    this.touchDelivery = TOUCH_DELIVERY.formatted(tableName);
    this.deleteExpiredDeliveries = DELETE_EXPIRED_DELIVERIES.formatted(tableName);
    this.deleteExpiredWorkflowStarts = DELETE_EXPIRED_WORKFLOW_STARTS.formatted(tableName);
    this.selectExpiredWorkflowStarts = SELECT_EXPIRED_WORKFLOW_STARTS.formatted(tableName);
    this.deleteWorkflowStart = DELETE_WORKFLOW_START.formatted(tableName);
    this.replaceWorkflowStart = REPLACE_WORKFLOW_START.formatted(tableName);
    this.deleteDeliveriesOfWorkflow = DELETE_DELIVERIES_OF_WORKFLOW.formatted(tableName);
    this.selectAdapterIdsOfOpenTasks = SELECT_ADAPTER_IDS_OF_OPEN_TASKS.formatted(tableName);
    this.selectAnyOpenRecord = SELECT_ANY_OPEN_RECORD.formatted(tableName);
    this.selectOpenTasksOfAggregate = SELECT_OPEN_TASKS_OF_AGGREGATE.formatted(tableName);
    this.selectOpenTasksOfWorkflow = SELECT_OPEN_TASKS_OF_WORKFLOW.formatted(tableName);
    this.touches = new OpenTaskTouches(tableName, this::refreshLastSeen);

  }

  /**
   * The table this store works on, which the messages around it name.
   * <p>
   * An operator who has to look at the records needs the name, and it is configurable, so
   * nothing may spell it out a second time.
   *
   * @return The name of the table the records are stored in
   */
  public String getTableName() {

    return tableName;

  }

  /**
   * The record of the given delivery.
   *
   * @param deliveryKey The delivery's identity
   * @return The record or {@link Optional#empty()}
   */
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(selectDelivery)) {
        statement.setString(1, deliveryKey);
        try (var resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return Optional.empty();
          }
          return Optional.of(readRecord(resultSet));
        }
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          "Could not read the record of task delivery '%s' from table '%s'!"
              .formatted(deliveryKey, tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * The record which left one task open, whether or not the task has been closed since -
   * what the election of a task operation reads instead of asking a BPMS (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#recordOfTask}).
   * <p>
   * A failure is NOT swallowed here, unlike the startup questions above: this read decides
   * whether an operation is routed at all, and a store which quietly answers "nothing" would
   * turn a broken table into a silently doubled number of BPMS round trips.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param taskId The BPMS' identity of the task
   * @return The record or {@link Optional#empty()} if there is none
   */
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(selectRecordOfTask)) {
        statement.setMaxRows(ROWS_OF_THE_NEWEST_RECORD);
        statement.setString(1, taskId);
        statement.setString(2, workflowModuleId);
        statement.setString(3, bpmnProcessId);
        statement.setString(4, workflowAggregateId);
        statement.setString(5, COMPLETION_PENDING);
        try (var resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return Optional.empty();
          }
          return Optional.of(readRecord(resultSet));
        }
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          """
              Could not read the record of task '%s' of workflow '%s' (BPMN process '%s' of \
              workflow module '%s') from table '%s'!"""
              .formatted(taskId, workflowAggregateId, bpmnProcessId, workflowModuleId, tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * The open tasks of one workflow aggregate (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#openTasksOfAggregate}), oldest
   * first.
   * <p>
   * A failure is NOT swallowed: a caller building a list of open work from an empty answer
   * would show an empty screen instead of an error, and nobody would notice the table is
   * broken.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @return The open records, oldest first
   */
  public List<TaskDelivery> openTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(selectOpenTasksOfAggregate)) {
        statement.setString(1, TASK_DELIVERY);
        statement.setString(2, workflowModuleId);
        statement.setString(3, bpmnProcessId);
        statement.setString(4, workflowAggregateId);
        statement.setString(5, COMPLETION_PENDING);
        try (var resultSet = statement.executeQuery()) {
          final var records = new java.util.ArrayList<TaskDelivery>();
          while (resultSet.next()) {
            records.add(readRecord(resultSet));
          }
          return List.copyOf(records);
        }
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          """
              Could not read the open tasks of workflow '%s' (BPMN process '%s' of workflow \
              module '%s') from table '%s'!"""
              .formatted(workflowAggregateId, bpmnProcessId, workflowModuleId, tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * The open tasks of one workflow of the BPMS (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#openTasksOfWorkflow}), oldest first.
   * <p>
   * A failure is NOT swallowed, for the reason the read by aggregate does not swallow one: a
   * caller reading an empty answer would derive nothing and nobody would notice the table is
   * broken.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param workflowId The BPMS' own id of the workflow
   * @return The open records, oldest first
   */
  public List<TaskDelivery> openTasksOfWorkflow(
      final String workflowModuleId,
      final String workflowId) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(selectOpenTasksOfWorkflow)) {
        statement.setString(1, TASK_DELIVERY);
        statement.setString(2, workflowModuleId);
        statement.setString(3, workflowId);
        statement.setString(4, COMPLETION_PENDING);
        try (var resultSet = statement.executeQuery()) {
          final var records = new java.util.ArrayList<TaskDelivery>();
          while (resultSet.next()) {
            records.add(readRecord(resultSet));
          }
          return List.copyOf(records);
        }
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          """
              Could not read the open tasks of workflow '%s' of workflow module '%s' from table \
              '%s'!"""
              .formatted(workflowId, workflowModuleId, tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Writes down that one task is over (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#markTaskClosed}). Runs in the
   * transaction of whoever calls it, so it commits with whatever that thread commits.
   * <p>
   * <code>TASK_CLOSED_AT IS NULL</code> keeps a repeated dispatch from moving the moment: the
   * task was closed when it was first closed, and the age of an open task is measured
   * against exactly such a fixed moment elsewhere in this table. The statement carries no
   * row limit, so every record naming that task is closed, which is decision 72 in the
   * repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param taskId The BPMS' identity of the closed task
   * @return The number of records this call marked
   */
  public int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(closeTask)) {
        statement.setTimestamp(1, Timestamp.from(Instant.now()));
        statement.setString(2, taskId);
        statement.setString(3, workflowModuleId);
        statement.setString(4, bpmnProcessId);
        statement.setString(5, workflowAggregateId);
        return statement.executeUpdate();
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          """
              Could not mark task '%s' of workflow '%s' (BPMN process '%s' of workflow module \
              '%s') as closed in table '%s'!"""
              .formatted(taskId, workflowAggregateId, bpmnProcessId, workflowModuleId, tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Reads one row into a record. Every statement selecting a whole record lists its columns
   * in the order of the record's components, which is what lets them share this and what
   * keeps the indexes below readable.
   *
   * @param resultSet The result set positioned on the row
   * @return The record it holds
   */
  private static TaskDelivery readRecord(
      final java.sql.ResultSet resultSet) throws SQLException {

    final var recordedAt = resultSet.getTimestamp(13);
    final var taskClosedAt = resultSet.getTimestamp(14);
    return new TaskDelivery(
        resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet
            .getString(4), resultSet.getString(5), resultSet.getString(6), resultSet
                .getString(7), resultSet.getString(8), resultSet.getString(9), resultSet
                    .getString(10), resultSet.getString(11), resultSet
                        .getString(12), recordedAt == null
                            ? null
                            : recordedAt.toInstant(), taskClosedAt == null
                                ? null
                                : taskClosedAt.toInstant(), resultSet.getString(15), resultSet
                                    .getString(16));

  }

  /**
   * The adapter ids the OPEN records of one BPMN process belong to: a record
   * whose outcome is <code>COMPLETION_PENDING</code> answers redeliveries of a task the
   * application has not completed yet, so its adapter id is one the configuration still
   * has to know.
   *
   * @param workflowModuleId The workflow module to ask about
   * @param bpmnProcessId The BPMN process to ask about
   * @return The adapter ids found, never <code>null</code>
   */
  public java.util.Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(selectAdapterIdsOfOpenTasks)) {
        statement.setString(1, TASK_DELIVERY);
        statement.setString(2, workflowModuleId);
        statement.setString(3, bpmnProcessId);
        statement.setString(4, COMPLETION_PENDING);
        try (var resultSet = statement.executeQuery()) {
          final var adapterIds = new java.util.LinkedHashSet<String>();
          while (resultSet.next()) {
            adapterIds.add(resultSet.getString(1));
          }
          return adapterIds;
        }
      }
    } catch (final SQLException e) {
      // a startup diagnosis must not be the reason an application fails to boot: the
      // question stays unanswered and the check is silent, which is what a store saying
      // "I cannot tell" does anyway
      log
          .debug(
              "Could not read the adapter ids of open task-delivery records of BPMN process '{}' "
                  + "(workflow module '{}') from table '{}'",
              bpmnProcessId,
              workflowModuleId,
              tableName,
              e);
      return java.util.Set.of();
    } finally {
      release(connection);
    }

  }

  /**
   * Writes the record within the transaction currently running.
   *
   * @param delivery What was processed
   * @return <code>true</code> if written, <code>false</code> if a record of the same
   *         delivery key existed already
   */
  public boolean record(
      final TaskDelivery delivery) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(insertDelivery)) {
        statement.setString(1, delivery.deliveryKey());
        statement.setString(2, delivery.adapterId());
        statement.setString(3, delivery.workflowModuleId());
        statement.setString(4, delivery.bpmnProcessId());
        statement.setString(5, delivery.workflowAggregateId());
        statement.setString(6, delivery.workflowId());
        statement.setString(7, delivery.taskDefinition());
        statement.setString(8, delivery.bpmnElementId());
        statement.setString(9, delivery.taskId());
        statement.setString(10, delivery.outcome());
        statement.setString(11, delivery.bpmnErrorCode());
        statement.setString(12, delivery.bpmnErrorName());
        final var recordedAt = Timestamp.from(delivery.recordedAt() == null
            ? Instant.now()
            : delivery.recordedAt());
        statement.setTimestamp(13, recordedAt);
        // the record was seen the moment it was written; a redelivery of a task which
        // stays open moves this one and leaves RECORDED_AT where it is
        statement.setTimestamp(14, recordedAt);
        statement.setString(15, delivery.taskKind());
        statement.setString(16, kindOfTheRow(delivery));
        statement.executeUpdate();
      }
      return true;
    } catch (final SQLException e) {
      if (isDuplicateKey(e)) {
        // another node processed the same delivery concurrently - it wrote the
        // record, and this transaction has nothing to add
        log.debug(
            "Task delivery '{}' of BPMN process '{}' of workflow module '{}' was recorded already",
            delivery.deliveryKey(),
            delivery.bpmnProcessId(),
            delivery.workflowModuleId());
        return false;
      }
      throw new RuntimeException(
          "Could not record task delivery '%s' of BPMN process '%s' of workflow module '%s' in table '%s'!"
              .formatted(
                  delivery.deliveryKey(),
                  delivery.bpmnProcessId(),
                  delivery.workflowModuleId(),
                  tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Writes the row about the start of a workflow, replacing the one of an earlier workflow of the
   * same aggregate (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#recordWorkflowStart}).
   * <p>
   * The insert comes first, because the ordinary case is that there is no row: an aggregate carries
   * one workflow. Only where the insert finds a row does the update run, and that update is bounded
   * to a row which is a start and whose workflow id differs - so a start dispatched twice writes
   * nothing and answers <code>false</code>.
   *
   * @param workflowStart The row to write
   * @return Whether the store now holds this workflow's id for the first time
   */
  public boolean recordWorkflowStart(
      final TaskDelivery workflowStart) {

    if (record(workflowStart)) {
      return true;
    }
    return replaceTheStartOfAnEarlierWorkflow(workflowStart);

  }

  /**
   * Replaces the id in the row of an earlier workflow of the same aggregate.
   *
   * @param workflowStart The row of the workflow which runs now
   * @return Whether a row was replaced, which is <code>false</code> where the row already named this
   *         workflow
   */
  private boolean replaceTheStartOfAnEarlierWorkflow(
      final TaskDelivery workflowStart) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(replaceWorkflowStart)) {
        final var startedAt = Timestamp.from(workflowStart.recordedAt() == null
            ? Instant.now()
            : workflowStart.recordedAt());
        statement.setString(1, workflowStart.workflowId());
        statement.setString(2, workflowStart.adapterId());
        statement.setTimestamp(3, startedAt);
        statement.setTimestamp(4, startedAt);
        statement.setString(5, workflowStart.deliveryKey());
        statement.setString(6, WORKFLOW_START);
        statement.setString(7, workflowStart.workflowId());
        return statement.executeUpdate() > 0;
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          """
              Could not write down that workflow '%s' of aggregate '%s' (BPMN process '%s' of \
              workflow module '%s') was started, over the row of an earlier workflow of that \
              aggregate, in table '%s'!"""
              .formatted(
                  workflowStart.workflowId(),
                  workflowStart.workflowAggregateId(),
                  workflowStart.bpmnProcessId(),
                  workflowStart.workflowModuleId(),
                  tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Remembers that the record of this delivery is still answering the redeliveries of an
   * open task, so the retention has to count from now rather than from the moment the
   * handler ran. Nothing is written here - the key is collected and the next
   * {@link #deleteExpired(Duration)} writes what accumulated.
   *
   * @param deliveryKey The delivery's identity
   */
  public void stillOpen(
      final String deliveryKey) {

    touches.remember(deliveryKey);

  }

  /**
   * Writes <code>LAST_SEEN_AT</code> for the open tasks redelivered since the last run.
   * Called by {@link #deleteExpired(Duration)} and usable on demand (e.g. by tests).
   *
   * @return The number of records refreshed
   */
  public int refreshOpenTasks() {

    return touches.flush();

  }

  /**
   * Writes <code>LAST_SEEN_AT</code> of one block of records, as a JDBC batch of the same
   * statement. A key whose record was deleted meanwhile updates nothing, which is the
   * right answer: the record is gone and the next redelivery writes a new one.
   *
   * @param deliveryKeys The keys of one block
   */
  private void refreshLastSeen(
      final List<String> deliveryKeys) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(touchDelivery)) {
        final var now = Timestamp.from(Instant.now());
        for (final var deliveryKey : deliveryKeys) {
          statement.setTimestamp(1, now);
          statement.setString(2, deliveryKey);
          statement.addBatch();
        }
        statement.executeBatch();
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          "Could not refresh the records of %d open tasks in table '%s'!"
              .formatted(deliveryKeys.size(), tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Refreshes the records of the open tasks redelivered since the last run and then
   * deletes the records nobody has seen for the given retention period - the
   * deduplication window closes with them. A plain, idempotent DELETE: several
   * application instances may run it concurrently.
   * <p>
   * The two belong together and in this order: refreshing first is what keeps the record
   * of a task which is still being redelivered, and deleting by <code>LAST_SEEN_AT</code>
   * is what lets the record of a task nobody redelivers any more expire after all.
   *
   * @param retention How long a record is kept
   * @return The number of records deleted
   */
  public int deleteExpired(
      final Duration retention) {

    refreshOpenTasks();

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(deleteExpiredDeliveries)) {
        statement.setString(1, TASK_DELIVERY);
        statement.setTimestamp(2, Timestamp.from(Instant.now().minus(retention)));
        return statement.executeUpdate();
      }
    } catch (final SQLException e) {
      log.warn("Could not clean up expired task-delivery records of table '{}'", tableName, e);
      return 0;
    } finally {
      release(connection);
    }

  }

  /**
   * Deletes the rows about started workflows whose own retention passed. Called by the same
   * hourly run which deletes the expired deliveries, with a period of its own: such a row is
   * read for as long as somebody may ask which workflow an aggregate belongs to, which outlasts
   * the workflow itself.
   * <p>
   * Without a sieve this is one indexed DELETE. With one, the expired rows are read first and
   * the sieve is asked per row, which costs a read of the application's own database per row and
   * is why an application switches it on rather than finding it on (see
   * {@link io.vanillabp.integration.spi.WorkflowStartSieve}). A row the sieve keeps is simply
   * left where it is and offered again at the next run.
   * <p>
   * A failure is swallowed like the one of the deliveries: it costs disk space, and the next run
   * tries again.
   *
   * @param retention How long the row about a started workflow is kept
   * @param sieve What decides per row whether it may go, or <code>null</code> to delete every
   *          expired row
   * @return The number of rows deleted
   */
  public int deleteExpiredWorkflowStarts(
      final Duration retention,
      final io.vanillabp.integration.spi.WorkflowStartSieve sieve) {

    final var expiredBefore = Timestamp.from(Instant.now().minus(retention));
    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (sieve == null) {
        try (var statement = connection.prepareStatement(deleteExpiredWorkflowStarts)) {
          statement.setString(1, WORKFLOW_START);
          statement.setTimestamp(2, expiredBefore);
          return statement.executeUpdate();
        }
      }
      return deleteWhatTheSieveLetsGo(connection, expiredBefore, sieve);
    } catch (final SQLException e) {
      log
          .warn(
              "Could not clean up the expired rows about started workflows of table '{}'",
              tableName,
              e);
      return 0;
    } finally {
      release(connection);
    }

  }

  /**
   * Reads the expired rows about started workflows, asks the sieve about each of them and
   * deletes the ones it lets go, as one JDBC batch.
   *
   * @param connection The connection of this run
   * @param expiredBefore The moment a row has to be older than
   * @param sieve What decides per row
   * @return The number of rows deleted
   */
  private int deleteWhatTheSieveLetsGo(
      final Connection connection,
      final Timestamp expiredBefore,
      final io.vanillabp.integration.spi.WorkflowStartSieve sieve) throws SQLException {

    final var expired = new java.util.ArrayList<TaskDelivery>();
    try (var statement = connection.prepareStatement(selectExpiredWorkflowStarts)) {
      statement.setMaxRows(WORKFLOW_STARTS_SIEVED_PER_RUN);
      statement.setString(1, WORKFLOW_START);
      statement.setTimestamp(2, expiredBefore);
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          expired.add(readRecord(resultSet));
        }
      }
    }
    final var mayGo = expired
        .stream()
        .filter(row -> Boolean.TRUE.equals(sieve.mayBeDeleted(row)))
        .map(TaskDelivery::deliveryKey)
        .toList();
    if (mayGo.isEmpty()) {
      return 0;
    }
    try (var statement = connection.prepareStatement(deleteWorkflowStart)) {
      for (final var deliveryKey : mayGo) {
        statement.setString(1, deliveryKey);
        statement.addBatch();
      }
      final var deleted = statement.executeBatch();
      var count = 0;
      for (final var rows : deleted) {
        count += Math.max(rows, 0);
      }
      return count;
    }

  }

  /**
   * Deletes the records of ONE ended workflow (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}). Runs in the
   * transaction of the end notification, so it commits with it.
   * <p>
   * Unlike {@link #deleteExpired(Duration)} a failure is NOT swallowed: the retention
   * cleanup runs again in an hour, this deletion has exactly one chance and belongs to a
   * transaction which has to know whether its work went through.
   *
   * @param workflowModuleId The workflow module of the ended workflow
   * @param bpmnProcessId The BPMN process of the ended workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @param recordedBefore Only records written before this moment are deleted - what
   *          keeps the records of a second workflow on the same aggregate
   * @return The number of records deleted
   */
  // No index ships for these columns: AGGREGATE_ID holds up to 1024 characters, and an
  // index over the three of them exceeds the key-length limit of MySQL (3072 bytes with
  // utf8mb4) and of a DB2 database using 4K pages. An application whose table grows large
  // adds one itself, prefixed the way its database needs it - the wiki says so.
  public int deleteRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Instant recordedBefore) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(deleteDeliveriesOfWorkflow)) {
        statement.setString(1, TASK_DELIVERY);
        statement.setString(2, workflowModuleId);
        statement.setString(3, bpmnProcessId);
        statement.setString(4, workflowAggregateId);
        statement.setTimestamp(5, Timestamp.from(recordedBefore));
        return statement.executeUpdate();
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          """
              Could not release the task-delivery records of workflow '%s' (BPMN process '%s' of \
              workflow module '%s') in table '%s'!"""
              .formatted(workflowAggregateId, bpmnProcessId, workflowModuleId, tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * The columns a table has to carry beyond the ones every version of VanillaBP wrote.
   * Only what was ADDED later belongs in here: a table which predates the addition exists
   * and looks fine, and the missing column would surface at the first write.
   */
  private static final List<AddedColumn> ADDED_COLUMNS = List
      .of(
          new AddedColumn(
              "LAST_SEEN_AT", "TIMESTAMP (the type your database uses for the existing column RECORDED_AT), filled with the value of RECORDED_AT and NOT NULL", "the records of the tasks your application leaves open cannot be kept alive - they would expire while the tasks are still being redelivered", null),
          new AddedColumn(
              "ADAPTER_ID", "VARCHAR(255) (nullable: a record written before the column existed has no adapter id)", "VanillaBP cannot tell at startup that an adapter id which open records still belong to is not configured any more, which is what a renamed adapter id looks like", null),
          new AddedColumn(
              "TASK_ID", "VARCHAR(255) (nullable: a record written before the column existed names no task)", "every completion or cancellation of a task has to ask the configured BPMS which of them holds it, although the record of that task already knows", "CREATE INDEX %s_TASK ON %s (TASK_ID)"),
          new AddedColumn(
              "TASK_CLOSED_AT", "TIMESTAMP (the type your database uses for the existing column RECORDED_AT), nullable", "a task which was completed already cannot be recognised from the record, so a repeated completion asks the BPMS before it becomes the no-op it always was", null),
          new AddedColumn(
              "BPMN_ELEMENT_ID", "VARCHAR(255) (nullable: a record written before the column existed names no element)", "a record does not say which element of the model it belongs to, so an extension listing the open tasks of a workflow cannot find the part of the model each of them stands for", null),
          new AddedColumn(
              "WORKFLOW_ID", "VARCHAR(255) (nullable: a record written before the column existed names no workflow)", "a record does not say which workflow of the BPMS it belongs to, so nobody can follow a task into the tooling of that BPMS and VanillaBP cannot read the other tasks it still believes are open in that workflow", INDEX_OF_WORKFLOW),
          new AddedColumn(
              "TASK_KIND", "VARCHAR(32) (nullable: a record written before the column existed names no kind)", "a record does not say whether its id is the id of a task or of a user task, so completing a task with the id of a user task is answered with everything it could be instead of what it is", null),
          new AddedColumn(
              "RECORD_KIND", "VARCHAR(32) DEFAULT 'TASK_DELIVERY' NOT NULL (the default is what fills the rows which are there, all of which are task deliveries)", "VanillaBP cannot write down which workflow of the BPMS an aggregate belongs to, so every operation on a workflow asks each configured BPMS which of them holds it, and on a BPMS answering from a read model a report right after the start finds no workflow at all", "DROP INDEX %s_OPEN and then CREATE INDEX %s_OPEN ON %s (RECORD_KIND, OUTCOME, TASK_CLOSED_AT)"));

  /**
   * A column a later version of VanillaBP added: its name, the statement which adds it and
   * what is lost without it. All three belong in the message, because that is the whole
   * remedy.
   *
   * @param name The column's name
   * @param definition What to add it as
   * @param whatIsLost What VanillaBP cannot do without it
   * @param indexStatement The statement creating the index the column is read by, with two
   *          placeholders for the table name, or <code>null</code> where the column is
   *          written and read without one
   */
  private record AddedColumn(
                             String name,
                             String definition,
                             String whatIsLost,
                             String indexStatement) {
  }

  /**
   * Creates the table (and the index the cleanup reads) unless it exists already. A table
   * which exists is checked for the columns a later version of VanillaBP added, because
   * creating nothing is the one case in which a table of an older version passes unnoticed.
   *
   * @throws IllegalStateException If the DDL fails - naming the way out (manage the
   *           schema manually)
   */
  public void createSchemaIfNotExists() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        validateColumns(connection);
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(buildCreateTable(connection, tableName));
        statement.executeUpdate(
            "CREATE INDEX %s_AGE ON %s (LAST_SEEN_AT)".formatted(tableName, tableName));
        // the election of a task operation looks a record up by the task the caller names,
        // once per operation - without this index that read is a table scan and costs more
        // than the BPMS round trip it saves. TASK_ID alone is selective (a BPMS names its
        // tasks uniquely) and stays well inside MySQL's key-length limit, which an index
        // spanning AGGREGATE_ID would not
        statement.executeUpdate(
            "CREATE INDEX %s_TASK ON %s (TASK_ID)".formatted(tableName, tableName));
        // the open tasks of one workflow aggregate are asked for once per screen an
        // extension builds, so that read must not walk everything ever recorded. Over
        // RECORD_KIND, OUTCOME and TASK_CLOSED_AT rather than over AGGREGATE_ID, which is 1024
        // characters wide and would exceed the key-length limit of MySQL and of DB2 with
        // 4K pages - see SELECT_OPEN_TASKS_OF_AGGREGATE
        statement.executeUpdate(INDEX_OF_OPEN_RECORDS.formatted(tableName, tableName));
        // the open tasks of ONE workflow of the BPMS are read once per wake-up of that
        // workflow, which is far more often than an extension builds a screen. WORKFLOW_ID
        // is VARCHAR(255) and selective, so it carries an index of its own - what
        // AGGREGATE_ID cannot - see SELECT_OPEN_TASKS_OF_WORKFLOW
        statement.executeUpdate(INDEX_OF_WORKFLOW.formatted(tableName, tableName));
      }
    } catch (final SQLException e) {
      if (createdConcurrently()) {
        return;
      }
      throw new IllegalStateException(
          """
              Could not create the task-delivery table '%s'! Set '%s' to \
              'false' and manage the schema manually if the DDL is not suitable for your database - \
              the table needs a unique DELIVERY_KEY and is described in the platform integration's \
              README."""
              .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Whether the DDL failed because another instance created the table between the check and the
   * statement. Two instances starting together both see no table and both create it, and the
   * loser's deployment is fine - it just has nothing left to do (see
   * {@link JdbcSchema#tableExistsQuietly(Connection, String)}).
   *
   * @return Whether the table is there now
   */
  private boolean createdConcurrently() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (!JdbcSchema.tableExistsQuietly(connection, tableName)) {
        return false;
      }
      log.debug(
          "The task-delivery table '{}' was created by another instance starting at the same moment",
          tableName);
      return true;
    } catch (final SQLException e) {
      return false;
    } finally {
      release(connection);
    }

  }

  /**
   * Verifies that the table exists AND carries the columns this version writes, for an
   * application which creates its schema itself.
   * <p>
   * Without this check a missing table surfaces at the first delivery, which is hours after the
   * deployment and looks like a bug of the application. The message names the table, the property
   * which would have created it and the artifact which contains the statements.
   * <p>
   * The columns are checked for the same reason: an application which applied an
   * earlier changelog of VanillaBP has the table but not everything the current version writes
   * into it, and a table which exists would otherwise pass the check and fail at the first
   * delivery.
   *
   * @throws IllegalStateException If the table or one of its columns is missing
   */
  public void validateSchemaExists() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        validateColumns(connection);
        return;
      }
      throw new IllegalStateException(
          """
              The task-delivery table '%s' does not exist! VanillaBP remembers every task delivery \
              it processed in it, so a BPMS repeating a delivery is answered from it instead of \
              running the handler twice, and so a later operation on one of those tasks knows which \
              BPMS holds it and which kind of id its id is. Either
              - apply the schema of VanillaBP with your migration tool: the artifact \
              'io.vanillabp:vanillabp-schema' ships the Liquibase changelog \
              'vanillabp/schema/changelog.xml' and the SQL generated from it for Flyway, or
              - let VanillaBP create the table by setting '%s' to \
              'true' (the default)."""
              .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY));
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Could not check whether the task-delivery table '%s' exists!".formatted(tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Verifies the columns which a later version of VanillaBP added to the table. The
   * message names the column, the statement which adds it and the artifact whose
   * changelog does it, because that is the whole remedy.
   *
   * @param connection The connection to the database holding the table
   * @throws IllegalStateException If a column is missing
   */
  private void validateColumns(
      final Connection connection) throws SQLException {

    for (final var column : ADDED_COLUMNS) {
      if (JdbcSchema.columnExists(connection, tableName, column.name())) {
        continue;
      }
      throw new IllegalStateException(
          """
              The task-delivery table '%s' has no column '%s'! It was added to the table of \
              VanillaBP after your database was created, and without it %s. Either
              - apply the current schema of VanillaBP with your migration tool: the artifact \
              'io.vanillabp:vanillabp-schema' ships the Liquibase changelog \
              'vanillabp/schema/changelog.xml' and the SQL generated from it for Flyway, or
              - add the column yourself: ALTER TABLE %s ADD %s %s.%s"""
              .formatted(tableName, column.name(), column.whatIsLost(), tableName, column.name(), column
                  .definition(),
                  column.indexStatement() == null
                      ? ""
                      : " It is read on a path which must not scan the table, so add the index it is looked up by as well: %s."
                          .formatted(column.indexStatement().formatted(tableName, tableName, tableName))));
    }

  }

  private void release(
      final Connection connection) {

    if (connection == null) {
      return;
    }
    try {
      connectionAccess.release(connection);
    } catch (final SQLException e) {
      log.warn("Could not release the connection used for the task-delivery table '{}'", tableName, e);
    }

  }

  /**
   * Whether the given exception signals a violated unique constraint (= the delivery
   * was recorded already).
   *
   * @param e The exception raised by the insert
   * @return Whether the insert failed due to a duplicate key
   */
  private static boolean isDuplicateKey(
      final SQLException e) {

    // PostgreSQL's JDBC driver does not map unique violations to the dedicated
    // subclass - fall back to the standard SQL state class 23 (integrity
    // constraint violation) for such drivers
    return (e instanceof SQLIntegrityConstraintViolationException) || ((e.getSQLState() != null) && e
        .getSQLState()
        .startsWith("23"));

  }

  /**
   * Builds the CREATE TABLE statement using a timestamp type suitable for the database:
   * SQL Server's <code>TIMESTAMP</code> is a row version (not a date-time), MySQL's has
   * auto-initialization quirks and ends in 2038.
   *
   * @param connection The connection used to detect the database
   * @param tableName The table to create
   * @return The CREATE TABLE statement
   */
  private static String buildCreateTable(
      final Connection connection,
      final String tableName) throws SQLException {

    final var product = connection
        .getMetaData()
        .getDatabaseProductName()
        .toLowerCase();
    final String timestampType;
    if (product.contains("microsoft")) {
      timestampType = "DATETIME2";
    } else if (product.contains("mysql") || product.contains("mariadb")) {
      timestampType = "DATETIME(6)";
    } else {
      timestampType = "TIMESTAMP";
    }
    return """
        CREATE TABLE %s (\
        DELIVERY_KEY VARCHAR(512) PRIMARY KEY, \
        ADAPTER_ID VARCHAR(255), \
        WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
        BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
        AGGREGATE_ID VARCHAR(1024), \
        TASK_DEFINITION VARCHAR(255), \
        TASK_ID VARCHAR(255), \
        OUTCOME VARCHAR(32), \
        BPMN_ERROR_CODE VARCHAR(255), \
        BPMN_ERROR_NAME VARCHAR(255), \
        RECORDED_AT %s NOT NULL, \
        LAST_SEEN_AT %s NOT NULL, \
        TASK_CLOSED_AT %s, \
        BPMN_ELEMENT_ID VARCHAR(255), \
        WORKFLOW_ID VARCHAR(255), \
        TASK_KIND VARCHAR(32), \
        RECORD_KIND VARCHAR(32) DEFAULT '%s' NOT NULL)"""
        .formatted(tableName, timestampType, timestampType, timestampType, TASK_DELIVERY);

  }

  /**
   * Whether an OPEN record of that BPMN process exists at all - see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#hasOpenRecords(String, String)}.
   * Unlike the adapter ids this distinguishes "none" from "cannot say", so a failing
   * query answers <code>null</code> rather than <code>false</code>.
   *
   * @param workflowModuleId The workflow module to ask about
   * @param bpmnProcessId The BPMN process to ask about
   * @return Whether an open record exists, <code>null</code> where the table could not be
   *         read
   */
  public Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(selectAnyOpenRecord)) {
        statement.setMaxRows(ROWS_OF_AN_EXISTENCE_QUESTION);
        statement.setString(1, TASK_DELIVERY);
        statement.setString(2, workflowModuleId);
        statement.setString(3, bpmnProcessId);
        statement.setString(4, COMPLETION_PENDING);
        try (var resultSet = statement.executeQuery()) {
          return resultSet.next();
        }
      }
    } catch (final SQLException e) {
      log
          .debug(
              "Could not read whether open task-delivery records of BPMN process '{}' (workflow "
                  + "module '{}') exist in table '{}'",
              bpmnProcessId,
              workflowModuleId,
              tableName,
              e);
      return null;
    } finally {
      release(connection);
    }

  }

}
