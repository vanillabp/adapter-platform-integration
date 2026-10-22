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
 * processed task deliveries. Everything which depends on the platform is behind
 * {@link JdbcConnectionAccess}. The connection has to belong to the transaction which
 * persists the workflow aggregate. Otherwise the record and the aggregate would not commit
 * together.
 * <p>
 * A row of this table is either a task delivery or the start of a workflow.
 * <code>RECORD_KIND</code> says which. Every question about open work filters on it first.
 * So a start, which has no task, is in none of those answers. And the retention of the
 * deliveries leaves it alone, because a start has a period of its own.
 * <p>
 * A record is INSERTed once and never rewritten. The delivery key is the primary key. So if
 * two nodes process the same delivery at the same time, there is one record, and the loser
 * learns it from the constraint violation. {@link #record(TaskDelivery)} then returns
 * <code>false</code>, exactly like a duplicate outbox entry. A row of a delivery which nobody
 * deduplicates has a key which belongs to that row alone. So it cannot lose such a race, and
 * nothing here treats it differently. Two columns change later, and nothing else does.
 * <code>LAST_SEEN_AT</code> is the moment the BPMS last redelivered the task which the record
 * answers. The retention counts from it. <code>TASK_CLOSED_AT</code> is the moment the
 * application's completion of that task reached the BPMS.
 * <p>
 * The DDL stays portable in the same way as the Quarkus outbox. Whether the table exists is
 * checked through the JDBC metadata, because Oracle and SQL Server do not support
 * <code>CREATE TABLE IF NOT EXISTS</code>. The timestamp type is chosen per database. The
 * delivery key is limited to
 * {@value io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryKey#MAX_LENGTH}
 * bytes of UTF-8. So the key-length limit of MySQL (3072 bytes with utf8mb4) is respected, and
 * so is the column on Oracle and DB2, which count a <code>VARCHAR</code> in bytes. The core
 * hashes longer keys before they ever reach a store.
 * <p>
 * Decision 6 in the repository's DECISIONS.md says why a record is written at all, and why it
 * has two timestamps. Decision 16 says where the schema comes from, and why the startup check
 * reads the columns. Decision 17 says why the adapter id is a column of its own. Decision 30
 * says why the record also answers which adapter holds a task.
 */
@Slf4j
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class JdbcTaskDeliveryStore {

  /**
   * The default name of the table which holds the records, used where the application names
   * none (<code>vanillabp.outbox.jdbc.delivery-table</code>).
   */
  public static final String DEFAULT_TABLE_NAME = "VANILLABP_TASK_DELIVERY";

  /**
   * Resolves the configured table name
   * (<code>vanillabp.outbox.jdbc.delivery-table</code>, or else {@link #DEFAULT_TABLE_NAME}).
   * Both platforms ask this method instead of reading the key themselves. So if an
   * application renames the table, the store, the startup check and the message about two
   * stores in one table all follow.
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
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND, PROCESS_VERSION \
      FROM %s \
      WHERE DELIVERY_KEY = ?""";

  /**
   * The record which left one task open. It is read once per task operation of the
   * application. The election asks it instead of asking every configured BPMS which of them
   * holds the task. TASK_ID has an index, so this is a lookup and not a scan. The other
   * columns narrow the answer down to the one workflow, because a task id is only unique
   * within its BPMS.
   * <p>
   * Only a record which reports <code>COMPLETION_PENDING</code> counts. That outcome leaves a
   * task open, and only such a task can be completed or cancelled later. The order is by
   * RECORDED_AT, so the most recent record answers where a task was delivered more than once.
   * A redelivery of an open task writes no second record, but a task which is cancelled and
   * created again does.
   */
  private static final String SELECT_RECORD_OF_TASK = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND, PROCESS_VERSION \
      FROM %s \
      WHERE TASK_ID = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? \
      AND OUTCOME = ? \
      ORDER BY RECORDED_AT DESC""";

  /**
   * How many rows the election reads: the newest record of that task, and nothing behind it.
   * It is set on the statement and not written as a LIMIT clause, for the reason which
   * {@link #ROWS_OF_AN_EXISTENCE_QUESTION} explains.
   */
  private static final int ROWS_OF_THE_NEWEST_RECORD = 1;

  /**
   * The OPEN records of one workflow aggregate: the outcome which left a task to the
   * application, and no moment which says that the completion reached the BPMS. The oldest
   * comes first, which is the order in which the tasks were handed out.
   * <p>
   * This reads the index {@link #INDEX_OF_OPEN_RECORDS}, over the kind of the row and the two
   * columns which say "open". AGGREGATE_ID is not part of it. The column holds up to 1024
   * characters. An index over it exceeds the key-length limit of MySQL (3072 bytes with
   * utf8mb4) and of a DB2 database with 4K pages. For the same reason the release of an ended
   * workflow ships without such an index. So the index narrows the read to the tasks which are
   * open right now. Their number follows what the BPMS hands out, not everything ever
   * recorded. The three equality columns are matched on those rows. An application with very
   * many tasks open at the same time adds an index over AGGREGATE_ID itself, with a prefix as
   * its database writes it. The README of migration-adapter says this as well.
   * <p>
   * The cost was measured on PostgreSQL 16.15 in September 2026, with the table in memory:
   * 0.22 ms at 501 open records, 0.68 ms at 5001, 5.73 ms at 50001 and 34.30 ms at 500001.
   * MongoDB took 0.27 ms at every one of those sizes, because it indexes the aggregate id
   * itself. PostgreSQL uses this index until the open records are about a fifth of the table.
   * Beyond that it reads the table itself. The cost is a straight line through that switch,
   * because either way one pass covers the open records. The history does not count: the
   * index scan examined the same 501 entries in 0.17 ms, whether the table held one million
   * records or two million. The README of migration-adapter has the numbers and the reasons
   * for shipping no index over AGGREGATE_ID.
   */
  private static final String SELECT_OPEN_TASKS_OF_AGGREGATE = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND, PROCESS_VERSION \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? \
      AND OUTCOME = ? AND TASK_CLOSED_AT IS NULL \
      ORDER BY RECORDED_AT ASC""";

  /**
   * The statement which creates the index through which the open tasks of an aggregate are
   * read. It has two placeholders for the table name, like every other DDL of this class.
   * <p>
   * RECORD_KIND comes FIRST, because it is the question asked first: is this row about a task
   * at all? Two equality columns can stand in either order, and the database does the same
   * with them. Only TASK_CLOSED_AT has to come last, because it is read as a range. So the
   * order is the one a reader follows.
   */
  private static final String INDEX_OF_OPEN_RECORDS = "CREATE INDEX %s_OPEN ON %s (RECORD_KIND, OUTCOME, TASK_CLOSED_AT)";

  /**
   * The OPEN records of ONE workflow of the BPMS, read on every wake-up of that workflow. The
   * oldest comes first. That is the order in which the tasks were handed out, and the order
   * which the probes of a wake-up follow.
   * <p>
   * Unlike AGGREGATE_ID, WORKFLOW_ID has an index of its own ({@link #INDEX_OF_WORKFLOW}). The
   * column is VARCHAR(255) and stays inside the key-length limit of every database this runs
   * on, the same way as TASK_ID. The README of migration-adapter has the measurement of what
   * that buys. The read by aggregate grows with the number of open tasks in the whole
   * installation. This read does not.
   * <p>
   * The BPMN process is NOT part of the question. A workflow of the BPMS is one instance. A
   * task which a called process handed out has the id of the secondary process, but it
   * belongs to the same instance. So narrowing by process would drop exactly those tasks.
   */
  private static final String SELECT_OPEN_TASKS_OF_WORKFLOW = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND, PROCESS_VERSION \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND WORKFLOW_ID = ? \
      AND OUTCOME = ? AND TASK_CLOSED_AT IS NULL \
      ORDER BY RECORDED_AT ASC""";

  /**
   * The statement which creates the index through which the open tasks of one workflow are
   * read. It has two placeholders for the table name, like every other DDL of this class.
   */
  private static final String INDEX_OF_WORKFLOW = "CREATE INDEX %s_WORKFLOW ON %s (WORKFLOW_ID)";

  /**
   * The adapter ids to which the OPEN records of one BPMN process belong. It is asked once
   * per BPMN process at startup, never at runtime, and answered from the same index which the
   * cleanup uses. A record written before ADAPTER_ID existed has none and is skipped.
   */
  private static final String SELECT_ADAPTER_IDS_OF_OPEN_TASKS = """
      SELECT DISTINCT ADAPTER_ID \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND OUTCOME = ? \
      AND ADAPTER_ID IS NOT NULL""";

  /**
   * Whether ANY open record of one BPMN process exists. The question is about existence, so
   * exactly one row is wanted, however many there are. See
   * {@link #ROWS_OF_AN_EXISTENCE_QUESTION} for why the row limit is set on the statement and
   * not written into this text.
   */
  private static final String SELECT_ANY_OPEN_RECORD = """
      SELECT DELIVERY_KEY \
      FROM %s \
      WHERE RECORD_KIND = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND OUTCOME = ?""";

  /**
   * How many rows a question about EXISTENCE fetches.
   * <p>
   * Reading the first row of a result set is not the same as asking for one row. A driver
   * decides for itself how much it fetches before <code>next()</code> answers. The PostgreSQL
   * driver reads the WHOLE result set into memory unless the statement says otherwise. So an
   * application with a hundred thousand open task deliveries would transfer a hundred
   * thousand keys while it boots, only to learn whether there is at least one. That is
   * growth with the age of the application, and decision 19 in the repository's DECISIONS.md
   * forbids it for a startup check.
   * <p>
   * It uses {@link java.sql.Statement#setMaxRows(int)} and not a <code>LIMIT</code> clause.
   * The syntax of that clause differs per database, but the JDBC call does not. And the call
   * reaches the server, because the extended query protocol carries the row count in the
   * message which executes the statement.
   */
  private static final int ROWS_OF_AN_EXISTENCE_QUESTION = 1;

  /**
   * The outcome of a delivery which left its task open. The three questions about open tasks
   * are only interested in these records.
   */
  private static final String COMPLETION_PENDING = io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome.Kind.COMPLETION_PENDING
      .name();

  /**
   * The kind of row which every question about open work filters on, and which the retention
   * of deliveries deletes by age. A row which says nothing about its kind was written before
   * the column existed, and it is a delivery. That is why the column is NOT NULL with this as
   * its default. An ALTER of an existing table fills it, and from then on the filter is a
   * plain equality.
   */
  private static final String TASK_DELIVERY = io.vanillabp.integration.spi.DeliveryRecordKind.TASK_DELIVERY
      .name();

  /**
   * The kind of row which says where a workflow runs. The retention of started workflows
   * deletes only this kind.
   */
  private static final String WORKFLOW_START = io.vanillabp.integration.spi.DeliveryRecordKind.WORKFLOW_START
      .name();

  /**
   * The kind under which a row is written: what the record says, or a delivery where it says
   * nothing.
   *
   * @param delivery The record about to be written
   * @return The text of the kind, never <code>null</code>
   */
  private static String kindOfTheRow(
      final TaskDelivery delivery) {

    // a kind which this version does not know is written as a delivery and not as itself.
    // The questions about open work and the two retentions compare with the two known kinds.
    // A third word would put the row in no answer and in no cleanup, and it would stay in
    // the table for good
    final var kind = io.vanillabp.integration.spi.DeliveryRecordKind.of(delivery.recordKind());
    return kind == null
        ? TASK_DELIVERY
        : kind.name();

  }

  private static final String INSERT_DELIVERY = """
      INSERT INTO %s \
      (DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      LAST_SEEN_AT, TASK_KIND, RECORD_KIND, PROCESS_VERSION) \
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

  // TASK_CLOSED_AT is not written here. A record starts open. The moment when the
  // application's completion reached the BPMS is the one thing about a task which is known
  // long after the handler ran
  private static final String CLOSE_TASK = """
      UPDATE %s \
      SET TASK_CLOSED_AT = ? \
      WHERE TASK_ID = ? AND WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? \
      AND TASK_CLOSED_AT IS NULL""";

  // one key per execution instead of an IN list. Every database limits the length of such
  // a list differently (Oracle at 1000 expressions, SQL Server at about 2100 parameters).
  // The statement runs as a JDBC batch, which has no such limit
  private static final String TOUCH_DELIVERY = """
      UPDATE %s \
      SET LAST_SEEN_AT = ? \
      WHERE DELIVERY_KEY = ?""";

  private static final String DELETE_EXPIRED_DELIVERIES = """
      DELETE FROM %s \
      WHERE RECORD_KIND = ? AND LAST_SEEN_AT < ?""";

  /**
   * The rows about started workflows whose own retention has passed. It uses LAST_SEEN_AT and
   * not RECORDED_AT, although both hold the same moment in such a row. Nothing ever moves
   * LAST_SEEN_AT of a start row. Only a redelivery of an open task does that, and a start row
   * answers no redelivery. Deleting by the column which the AGE index spans keeps this an
   * indexed delete and not a scan.
   */
  private static final String DELETE_EXPIRED_WORKFLOW_STARTS = """
      DELETE FROM %s \
      WHERE RECORD_KIND = ? AND LAST_SEEN_AT < ?""";

  /**
   * The rows about started workflows which the sieve is asked about. Where an application
   * asked for the sieve, they are read instead of deleted. The row limit of one run is
   * {@link #WORKFLOW_STARTS_SIEVED_PER_RUN}. The next run reaches what this one did not.
   */
  private static final String SELECT_EXPIRED_WORKFLOW_STARTS = """
      SELECT DELIVERY_KEY, ADAPTER_ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, WORKFLOW_ID, \
      TASK_DEFINITION, BPMN_ELEMENT_ID, TASK_ID, OUTCOME, BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT, \
      TASK_CLOSED_AT, TASK_KIND, RECORD_KIND, PROCESS_VERSION \
      FROM %s \
      WHERE RECORD_KIND = ? AND LAST_SEEN_AT < ? \
      ORDER BY LAST_SEEN_AT ASC""";

  /**
   * How many rows one sieved run looks at. The sieve costs one read of the application's own
   * database per row. So a run has an upper limit, instead of taking however many rows
   * expired while the application was stopped. The next hourly run goes on where this one
   * stopped, because what it kept is kept, and what it deleted is gone.
   */
  private static final int WORKFLOW_STARTS_SIEVED_PER_RUN = 1000;

  private static final String DELETE_WORKFLOW_START = """
      DELETE FROM %s \
      WHERE DELIVERY_KEY = ?""";

  /**
   * What a SECOND workflow of one aggregate writes. The row is keyed by the aggregate and the
   * BPMN process. So the insert of such a start finds the row of the workflow which ended
   * before it, and the id in that row has to be replaced. It only touches a row which really
   * is a start and whose id really differs. So a start which is dispatched twice updates
   * nothing.
   * <p>
   * LAST_SEEN_AT moves with it, and the period of a start row counts from it. The row is
   * about the workflow which runs now, so its age is the age of that workflow.
   * PROCESS_VERSION moves as well, also to empty. A version of the workflow which ended says
   * nothing about the one which runs now.
   */
  private static final String REPLACE_WORKFLOW_START = """
      UPDATE %s \
      SET WORKFLOW_ID = ?, ADAPTER_ID = ?, RECORDED_AT = ?, LAST_SEEN_AT = ?, PROCESS_VERSION = ? \
      WHERE DELIVERY_KEY = ? AND RECORD_KIND = ? \
      AND (WORKFLOW_ID IS NULL OR WORKFLOW_ID <> ?)""";

  /**
   * What a second report of the SAME start adds: the version, where the row names this
   * workflow and has no version yet. The adapter which started a workflow may report its
   * start, and the BPMS itself may report it again. Only one of the two may know the
   * version. A version which is there already stays, because the workflow started on it.
   */
  private static final String ADD_THE_VERSION_OF_A_WORKFLOW_START = """
      UPDATE %s \
      SET PROCESS_VERSION = ? \
      WHERE DELIVERY_KEY = ? AND RECORD_KIND = ? AND WORKFLOW_ID = ? AND PROCESS_VERSION IS NULL""";

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

  private final String addTheVersionOfAWorkflowStart;

  private final String deleteDeliveriesOfWorkflow;

  private final String selectAdapterIdsOfOpenTasks;

  private final String selectAnyOpenRecord;

  private final String selectOpenTasksOfAggregate;

  private final String selectOpenTasksOfWorkflow;

  private final OpenTaskTouches touches;

  /**
   * Built by the platform's delivery log, once per application.
   * <p>
   * The statements are built here and not per call. The table name is the only thing about
   * them which can differ, and it is known now. Nothing touches the database yet. The schema
   * is checked when the log starts.
   *
   * @param connectionAccess Where a connection of the running transaction comes from. That
   *          makes a record commit together with the workflow aggregate
   * @param tableName The table which holds the records, {@link #DEFAULT_TABLE_NAME} unless
   *          the application configured another one
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
    this.addTheVersionOfAWorkflowStart = ADD_THE_VERSION_OF_A_WORKFLOW_START.formatted(tableName);
    this.deleteDeliveriesOfWorkflow = DELETE_DELIVERIES_OF_WORKFLOW.formatted(tableName);
    this.selectAdapterIdsOfOpenTasks = SELECT_ADAPTER_IDS_OF_OPEN_TASKS.formatted(tableName);
    this.selectAnyOpenRecord = SELECT_ANY_OPEN_RECORD.formatted(tableName);
    this.selectOpenTasksOfAggregate = SELECT_OPEN_TASKS_OF_AGGREGATE.formatted(tableName);
    this.selectOpenTasksOfWorkflow = SELECT_OPEN_TASKS_OF_WORKFLOW.formatted(tableName);
    this.touches = new OpenTaskTouches(tableName, this::refreshLastSeen);

  }

  /**
   * The table this store works on. The messages around it name it.
   * <p>
   * An operator who has to look at the records needs the name. It can be configured, so
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
   * The record which left one task open, whether or not the task has been closed since. The
   * election of a task operation reads it instead of asking a BPMS (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#recordOfTask}).
   * <p>
   * Unlike the startup questions above, a failure is NOT swallowed here. This read decides
   * whether an operation is routed at all. A store which quietly answered "nothing" would
   * turn a broken table into twice as many BPMS round trips, and nobody would notice.
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
   * A failure is NOT swallowed. A caller which builds a list of open work from an empty
   * answer would show an empty screen instead of an error. Nobody would notice that the
   * table is broken.
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
   * A failure is NOT swallowed, for the same reason as in the read by aggregate. A caller
   * which reads an empty answer would derive nothing, and nobody would notice that the table
   * is broken.
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
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#markTaskClosed}). It runs in the
   * transaction of whoever calls it, so it commits with whatever that thread commits.
   * <p>
   * <code>TASK_CLOSED_AT IS NULL</code> keeps a repeated dispatch from moving the moment. The
   * task was closed when it was first closed. Elsewhere in this table the age of an open task
   * is measured against exactly such a fixed moment. The statement has no row limit, so every
   * record which names that task is closed. That is decision 72 in the repository's
   * DECISIONS.md.
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
   * Reads one row into a record. Every statement which selects a whole record lists its
   * columns in the order of the record's components. That lets them share this method, and
   * it keeps the indexes below readable.
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
                                    .getString(16), resultSet.getString(17));

  }

  /**
   * The adapter ids to which the OPEN records of one BPMN process belong. A record with the
   * outcome <code>COMPLETION_PENDING</code> answers redeliveries of a task which the
   * application has not completed yet. So its adapter id is one which the configuration
   * still has to know.
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
      // a startup diagnosis must not be the reason why an application fails to boot. The
      // question stays unanswered and the check is silent. A store which says "I cannot tell"
      // does the same anyway
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
   * <p>
   * The insert runs behind a savepoint. Where it meets a row of the same key, only the insert
   * is rolled back, and the transaction goes on. PostgreSQL needs this. It ends the whole
   * transaction at the first statement which fails, while H2, MySQL, Oracle and SQL Server go
   * on after it. Without the savepoint, "recorded already" would be the truth on those and a
   * lost transaction on PostgreSQL. Two cases meet a row of the same key on purpose. A
   * second workflow of an aggregate meets the start row of the first one and then replaces
   * it. And two nodes which process the same delivery at the same time both insert, where
   * the second one learns from the key that the first one was faster. See decision 131 in
   * the repository's DECISIONS.md.
   *
   * @param delivery What was processed
   * @return <code>true</code> if written, <code>false</code> if a record of the same
   *         delivery key existed already
   */
  public boolean record(
      final TaskDelivery delivery) {

    Connection connection = null;
    java.sql.Savepoint savepoint = null;
    try {
      connection = connectionAccess.acquire();
      savepoint = savepointOf(connection);
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
        // the record was seen at the moment it was written. A redelivery of a task which
        // stays open moves this one, and leaves RECORDED_AT where it is
        statement.setTimestamp(14, recordedAt);
        statement.setString(15, delivery.taskKind());
        statement.setString(16, kindOfTheRow(delivery));
        statement.setString(17, delivery.processVersion());
        statement.executeUpdate();
      }
      releaseQuietly(connection, savepoint);
      return true;
    } catch (final SQLException e) {
      if (isDuplicateKey(e)) {
        tookBackTheInsert(connection, savepoint, e);
        // another node processed the same delivery at the same time. It wrote the record,
        // and this transaction has nothing to add
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
   * Writes the row about the start of a workflow. It replaces the row of an earlier workflow
   * of the same aggregate (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#recordWorkflowStart}).
   * <p>
   * The insert comes first, because usually there is no row: an aggregate has one workflow.
   * Only where the insert finds a row does the update run. That update only touches a row
   * which is a start and whose workflow id differs. So a start which is dispatched twice
   * writes nothing and answers <code>false</code>. A row which already names this workflow
   * may still get the version it lacks, and nothing else.
   *
   * @param workflowStart The row to write
   * @return Whether the store now holds this workflow's id or its version for the first time
   */
  public boolean recordWorkflowStart(
      final TaskDelivery workflowStart) {

    if (record(workflowStart)) {
      return true;
    }
    if (replaceTheStartOfAnEarlierWorkflow(workflowStart)) {
      return true;
    }
    return addTheVersionOfTheSameWorkflow(workflowStart);

  }

  /**
   * Writes the version into the row of the same workflow, where that row has none yet.
   *
   * @param workflowStart The row of the workflow which runs now
   * @return Whether the version was added
   */
  private boolean addTheVersionOfTheSameWorkflow(
      final TaskDelivery workflowStart) {

    if (workflowStart.processVersion() == null) {
      return false;
    }
    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(addTheVersionOfAWorkflowStart)) {
        statement.setString(1, workflowStart.processVersion());
        statement.setString(2, workflowStart.deliveryKey());
        statement.setString(3, WORKFLOW_START);
        statement.setString(4, workflowStart.workflowId());
        return statement.executeUpdate() > 0;
      }
    } catch (final SQLException e) {
      throw new RuntimeException(
          """
              Could not write down version '%s' of workflow '%s' of aggregate '%s' (BPMN process \
              '%s' of workflow module '%s') in table '%s'!"""
              .formatted(
                  workflowStart.processVersion(),
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
   * Replaces the id in the row of an earlier workflow of the same aggregate.
   *
   * @param workflowStart The row of the workflow which runs now
   * @return Whether a row was replaced. It is <code>false</code> where the row already named
   *         this workflow
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
        statement.setString(5, workflowStart.processVersion());
        statement.setString(6, workflowStart.deliveryKey());
        statement.setString(7, WORKFLOW_START);
        statement.setString(8, workflowStart.workflowId());
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
   * Remembers that the record of this delivery still answers the redeliveries of an open
   * task. So the retention has to count from now, not from the moment the handler ran.
   * Nothing is written here. The key is collected, and the next
   * {@link #deleteExpired(Duration)} writes what has accumulated.
   *
   * @param deliveryKey The delivery's identity
   */
  public void stillOpen(
      final String deliveryKey) {

    touches.remember(deliveryKey);

  }

  /**
   * Writes <code>LAST_SEEN_AT</code> for the open tasks redelivered since the last run.
   * {@link #deleteExpired(Duration)} calls it, and it can be called on demand (for example by
   * tests).
   *
   * @return The number of records refreshed
   */
  public int refreshOpenTasks() {

    return touches.flush();

  }

  /**
   * Writes <code>LAST_SEEN_AT</code> of one block of records, as a JDBC batch of the same
   * statement. A key whose record was deleted in the meantime updates nothing. That is the
   * right answer: the record is gone, and the next redelivery writes a new one.
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
   * Refreshes the records of the open tasks redelivered since the last run. Then it deletes
   * the records which nobody has seen for the given retention period. The window of
   * deduplication closes with them. It is a plain DELETE which can be repeated safely, so
   * several application instances may run it at the same time.
   * <p>
   * The two steps belong together, in this order. Refreshing first keeps the record of a task
   * which is still being redelivered. Deleting by <code>LAST_SEEN_AT</code> lets the record of
   * a task which nobody redelivers any more expire after all.
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
   * Deletes the rows about started workflows whose own retention has passed. The same hourly
   * run which deletes the expired deliveries calls it, with a period of its own. Such a row
   * is read for as long as somebody may ask which workflow an aggregate belongs to, and that
   * is longer than the workflow itself.
   * <p>
   * Without a sieve this is one indexed DELETE. With a sieve, the expired rows are read first
   * and the sieve is asked for each row. That costs a read of the application's own database
   * per row. That is why an application has to switch it on, and does not find it on (see
   * {@link io.vanillabp.integration.spi.WorkflowStartSieve}). A row which the sieve keeps
   * simply stays where it is and is offered again at the next run.
   * <p>
   * A failure is swallowed, like the one of the deliveries. It costs disk space, and the
   * next run tries again.
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
   * Reads the expired rows about started workflows, asks the sieve about each of them, and
   * deletes the ones it lets go, as one JDBC batch.
   *
   * @param connection The connection of this run
   * @param expiredBefore A row has to be older than this moment
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
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}). It runs in the
   * transaction of the end notification, so it commits with it.
   * <p>
   * Unlike {@link #deleteExpired(Duration)}, a failure is NOT swallowed. The retention cleanup
   * runs again in an hour. This deletion has exactly one chance, and it belongs to a
   * transaction which has to know whether its work went through.
   *
   * @param workflowModuleId The workflow module of the ended workflow
   * @param bpmnProcessId The BPMN process of the ended workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @param recordedBefore Only records written before this moment are deleted. That keeps
   *          the records of a second workflow on the same aggregate
   * @return The number of records deleted
   */
  // No index ships for these columns. AGGREGATE_ID holds up to 1024 characters, and an
  // index over the three of them exceeds the key-length limit of MySQL (3072 bytes with
  // utf8mb4) and of a DB2 database with 4K pages. An application whose table grows large
  // adds one itself, with a prefix as its database needs it. The wiki says so.
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
   * The columns which a table must have beyond the ones every version of VanillaBP wrote.
   * Only what was ADDED later belongs here. A table which is older than the addition exists
   * and looks fine, and the missing column would only show up at the first write.
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
              "RECORD_KIND", "VARCHAR(32) DEFAULT 'TASK_DELIVERY' NOT NULL (the default is what fills the rows which are there, all of which are task deliveries)", "VanillaBP cannot write down which workflow of the BPMS an aggregate belongs to, so every operation on a workflow asks each configured BPMS which of them holds it, and on a BPMS answering from a read model a report right after the start finds no workflow at all", "DROP INDEX %s_OPEN and then CREATE INDEX %s_OPEN ON %s (RECORD_KIND, OUTCOME, TASK_CLOSED_AT)"),
          new AddedColumn(
              "PROCESS_VERSION", "VARCHAR(255) (nullable: a record written before the column existed names no version)", "a row does not say which version of its process a workflow runs on, so an extension which shows details per version cannot tell which details belong to a workflow until the BPMS itself can be asked", null));

  /**
   * A column which a later version of VanillaBP added: its name, the statement which adds it,
   * and what is lost without it. All three belong in the message, because together they are
   * the whole remedy.
   *
   * @param name The column's name
   * @param definition What to add it as
   * @param whatIsLost What VanillaBP cannot do without it
   * @param indexStatement The statement which creates the index the column is read by, with
   *          two placeholders for the table name. <code>null</code> where the column is
   *          written and read without an index
   */
  private record AddedColumn(
                             String name,
                             String definition,
                             String whatIsLost,
                             String indexStatement) {
  }

  /**
   * Creates the table, and the index which the cleanup reads, unless it exists already. A
   * table which exists is checked for the columns which a later version of VanillaBP added.
   * If nothing is created, that is the one case in which a table of an older version would
   * pass unnoticed.
   *
   * @throws IllegalStateException If the DDL fails. The message names the way out (manage
   *           the schema manually)
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
        // the election of a task operation looks up a record by the task which the caller
        // names, once per operation. Without this index that read is a table scan, and it
        // costs more than the BPMS round trip it saves. TASK_ID alone is selective (a BPMS
        // names its tasks uniquely), and it stays well inside MySQL's key-length limit. An
        // index which spans AGGREGATE_ID would not
        statement.executeUpdate(
            "CREATE INDEX %s_TASK ON %s (TASK_ID)".formatted(tableName, tableName));
        // an extension asks for the open tasks of one workflow aggregate once per screen it
        // builds. So that read must not walk everything ever recorded. The index is over
        // RECORD_KIND, OUTCOME and TASK_CLOSED_AT, not over AGGREGATE_ID. That column is 1024
        // characters wide and would exceed the key-length limit of MySQL and of DB2 with 4K
        // pages. See SELECT_OPEN_TASKS_OF_AGGREGATE
        statement.executeUpdate(INDEX_OF_OPEN_RECORDS.formatted(tableName, tableName));
        // the open tasks of ONE workflow of the BPMS are read once per wake-up of that
        // workflow, which is far more often than an extension builds a screen. WORKFLOW_ID is
        // VARCHAR(255) and selective, so it has an index of its own, which AGGREGATE_ID
        // cannot have. See SELECT_OPEN_TASKS_OF_WORKFLOW
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
   * Whether the DDL failed because another instance created the table between the check and
   * the statement. Two instances which start together both see no table, and both create it.
   * The deployment of the loser is fine. It just has nothing left to do (see
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
   * Checks that the table exists AND has the columns which this version writes. This is for
   * an application which creates its schema itself.
   * <p>
   * Without this check a missing table shows up at the first delivery. That is hours after
   * the deployment, and it looks like a bug of the application. The message names the table,
   * the property which would have created it, and the artifact which contains the
   * statements.
   * <p>
   * The columns are checked for the same reason. An application which applied an earlier
   * changelog of VanillaBP has the table, but not everything which the current version writes
   * into it. Without the check, a table which exists would pass and fail at the first
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
   * Checks the columns which a later version of VanillaBP added to the table. The message
   * names the column, the statement which adds it, and the artifact whose changelog does
   * that, because together they are the whole remedy.
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
   * The name of the savepoint behind an insert. It has a name, so that it can also be rolled
   * back by a statement, see {@link #tookBackTheInsert}.
   */
  private static final String SAVEPOINT = "VANILLABP_RECORD";

  /**
   * A savepoint in the transaction of the given connection, so that a failed insert can be
   * taken back without the rest of the transaction.
   *
   * @param connection The connection of the running transaction
   * @return The savepoint, or <code>null</code> where the connection has no transaction
   *         (auto-commit) or the driver does not offer savepoints. Then the insert runs as
   *         it did before
   */
  private static java.sql.Savepoint savepointOf(
      final Connection connection) {

    try {
      if (connection.getAutoCommit()) {
        return null;
      }
      return connection.setSavepoint(SAVEPOINT);
    } catch (final SQLException | UnsupportedOperationException e) {
      return null;
    }

  }

  /**
   * Gives the savepoint back after the insert went through. Oracle, for one, does not
   * support it, and a savepoint which is not given back ends with the transaction anyway.
   */
  private static void releaseQuietly(
      final Connection connection,
      final java.sql.Savepoint savepoint) {

    if (savepoint == null) {
      return;
    }
    try {
      connection.releaseSavepoint(savepoint);
    } catch (final SQLException | UnsupportedOperationException e) {
      // nothing to do, see above
    }

  }

  /**
   * Takes the failed insert back, and nothing else.
   * <p>
   * The pool of Quarkus (Agroal) refuses <code>Connection#rollback(Savepoint)</code> while
   * the connection takes part in a JTA transaction. So where that call is refused, the same
   * is done by a statement, which the pool does not look at. H2, PostgreSQL, MySQL, Oracle
   * and DB2 understand <code>ROLLBACK TO SAVEPOINT</code>. Where neither works, the insert
   * is left as it is, as before the savepoint existed. That only matters on PostgreSQL, and
   * there the statement works.
   *
   * @param failure The failure of the insert, which gets what went wrong here as suppressed
   */
  private static void tookBackTheInsert(
      final Connection connection,
      final java.sql.Savepoint savepoint,
      final SQLException failure) {

    if (savepoint == null) {
      return;
    }
    try {
      connection.rollback(savepoint);
      return;
    } catch (final SQLException | UnsupportedOperationException e) {
      failure.addSuppressed(e);
    }
    try (var statement = connection.createStatement()) {
      statement.execute("ROLLBACK TO SAVEPOINT "
          + SAVEPOINT);
    } catch (final SQLException e) {
      failure.addSuppressed(e);
    }

  }

  /**
   * Whether the given exception signals a violated unique constraint, which means that the
   * delivery was recorded already.
   *
   * @param e The exception raised by the insert
   * @return Whether the insert failed due to a duplicate key
   */
  private static boolean isDuplicateKey(
      final SQLException e) {

    // the JDBC driver of PostgreSQL does not map unique violations to the special subclass.
    // For such drivers, fall back to the standard SQL state class 23 (integrity constraint
    // violation)
    return (e instanceof SQLIntegrityConstraintViolationException) || ((e.getSQLState() != null) && e
        .getSQLState()
        .startsWith("23"));

  }

  /**
   * Builds the CREATE TABLE statement with a timestamp type which suits the database. The
   * <code>TIMESTAMP</code> of SQL Server is a row version, not a date and time. The one of
   * MySQL has odd automatic initialization, and it ends in 2038.
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
        RECORD_KIND VARCHAR(32) DEFAULT '%s' NOT NULL, \
        PROCESS_VERSION VARCHAR(255))"""
        .formatted(tableName, timestampType, timestampType, timestampType, TASK_DELIVERY);

  }

  /**
   * Whether an OPEN record of that BPMN process exists at all (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#hasOpenRecords(String, String)}).
   * Unlike the adapter ids, this tells "none" from "cannot say". So a failing query answers
   * <code>null</code> and not <code>false</code>.
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
