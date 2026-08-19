package io.vanillabp.integration.adapter.migration.delivery;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
 * A record is INSERTed once and never updated: the delivery key is the primary key, so
 * two nodes processing the same delivery concurrently end up with one record and the
 * loser learns it from the constraint violation ({@link #record(TaskDelivery)} returns
 * <code>false</code> then, exactly like a duplicate outbox entry).
 * <p>
 * The DDL is kept portable the same way the Quarkus outbox does it: table existence is
 * checked via JDBC metadata (<code>CREATE TABLE IF NOT EXISTS</code> is not supported
 * by Oracle and SQL Server), the timestamp type is chosen per database and the delivery
 * key is limited to {@value io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryKey#MAX_LENGTH}
 * characters so MySQL's key-length limit (3072 bytes with utf8mb4) is respected - the
 * core hashes longer keys before they ever reach a store.
 */
@Slf4j
public class JdbcTaskDeliveryStore {

  /**
   * The default name of the table holding the records.
   */
  public static final String DEFAULT_TABLE_NAME = "VANILLABP_TASK_DELIVERY";

  private static final String SELECT_DELIVERY = """
      SELECT WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, TASK_DEFINITION, OUTCOME, \
      BPMN_ERROR_CODE, BPMN_ERROR_NAME \
      FROM %s \
      WHERE DELIVERY_KEY = ?""";

  private static final String INSERT_DELIVERY = """
      INSERT INTO %s \
      (DELIVERY_KEY, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, AGGREGATE_ID, TASK_DEFINITION, OUTCOME, \
      BPMN_ERROR_CODE, BPMN_ERROR_NAME, RECORDED_AT) \
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

  private static final String DELETE_EXPIRED_DELIVERIES = """
      DELETE FROM %s \
      WHERE RECORDED_AT < ?""";

  private static final String DELETE_DELIVERIES_OF_WORKFLOW = """
      DELETE FROM %s \
      WHERE WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ? AND RECORDED_AT < ?""";

  private final JdbcConnectionAccess connectionAccess;

  private final String tableName;

  private final String selectDelivery;

  private final String insertDelivery;

  private final String deleteExpiredDeliveries;

  private final String deleteDeliveriesOfWorkflow;

  public JdbcTaskDeliveryStore(
      final JdbcConnectionAccess connectionAccess,
      final String tableName) {

    this.connectionAccess = connectionAccess;
    this.tableName = tableName;
    this.selectDelivery = SELECT_DELIVERY.formatted(tableName);
    this.insertDelivery = INSERT_DELIVERY.formatted(tableName);
    this.deleteExpiredDeliveries = DELETE_EXPIRED_DELIVERIES.formatted(tableName);
    this.deleteDeliveriesOfWorkflow = DELETE_DELIVERIES_OF_WORKFLOW.formatted(tableName);

  }

  /**
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
          return Optional
              .of(
                  new TaskDelivery(
                      deliveryKey, resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet
                          .getString(4), resultSet.getString(5), resultSet.getString(6), resultSet.getString(7)));
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
        statement.setString(2, delivery.workflowModuleId());
        statement.setString(3, delivery.bpmnProcessId());
        statement.setString(4, delivery.workflowAggregateId());
        statement.setString(5, delivery.taskDefinition());
        statement.setString(6, delivery.outcome());
        statement.setString(7, delivery.bpmnErrorCode());
        statement.setString(8, delivery.bpmnErrorName());
        statement.setTimestamp(9, Timestamp.from(Instant.now()));
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
   * Deletes the records older than the given retention period - the deduplication
   * window closes with them. A plain, idempotent DELETE: several application instances
   * may run it concurrently.
   *
   * @param retention How long a record is kept
   * @return The number of records deleted
   */
  public int deleteExpired(
      final Duration retention) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(deleteExpiredDeliveries)) {
        statement.setTimestamp(1, Timestamp.from(Instant.now().minus(retention)));
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
        statement.setString(1, workflowModuleId);
        statement.setString(2, bpmnProcessId);
        statement.setString(3, workflowAggregateId);
        statement.setTimestamp(4, Timestamp.from(recordedBefore));
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
   * Creates the table (and the index the cleanup reads) unless it exists already.
   *
   * @throws IllegalStateException If the DDL fails - naming the way out (manage the
   *           schema manually)
   */
  public void createSchemaIfNotExists() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (tableExists(connection, tableName)) {
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(buildCreateTable(connection, tableName));
        statement.executeUpdate(
            "CREATE INDEX %s_AGE ON %s (RECORDED_AT)".formatted(tableName, tableName));
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(
          """
              Could not create the task-delivery table '%s'! Set 'vanillabp.outbox.create-schema' to \
              'false' and manage the schema manually if the DDL is not suitable for your database - \
              the table needs a unique DELIVERY_KEY and is described in the platform integration's \
              README."""
              .formatted(tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Verifies that the table exists, for an application which creates its schema itself (story 75).
   * <p>
   * Without this check a missing table surfaces at the first delivery, which is hours after the
   * deployment and looks like a bug of the application. The message names the table, the property
   * which would have created it and the artifact which contains the statements.
   *
   * @throws IllegalStateException If the table is missing
   */
  public void validateSchemaExists() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (tableExists(connection, tableName)) {
        return;
      }
      throw new IllegalStateException(
          """
              The task-delivery table '%s' does not exist! VanillaBP remembers every task delivery \
              it processed in it, so a BPMS repeating a delivery is answered from it instead of \
              running the handler twice. Either
              - apply the schema of VanillaBP with your migration tool: the artifact \
              'io.vanillabp:vanillabp-schema' ships the Liquibase changelog \
              'vanillabp/schema/changelog.xml' and the SQL generated from it for Flyway, or
              - let VanillaBP create the table by setting 'vanillabp.outbox.create-schema' to \
              'true' (the default)."""
              .formatted(tableName));
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Could not check whether the task-delivery table '%s' exists!".formatted(tableName), e);
    } finally {
      release(connection);
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

  private static boolean tableExists(
      final Connection connection,
      final String tableName) throws SQLException {

    final var metaData = connection.getMetaData();
    // unquoted identifiers are folded to upper case by some databases (Oracle, H2)
    // and to lower case by others (PostgreSQL) - check both spellings
    for (final var name : List.of(
        tableName,
        tableName.toLowerCase())) {
      try (var tables = metaData.getTables(null, null, name, new String[]{
          "TABLE"
      })) {
        if (tables.next()) {
          return true;
        }
      }
    }
    return false;

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
        WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
        BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
        AGGREGATE_ID VARCHAR(1024), \
        TASK_DEFINITION VARCHAR(255), \
        OUTCOME VARCHAR(32) NOT NULL, \
        BPMN_ERROR_CODE VARCHAR(255), \
        BPMN_ERROR_NAME VARCHAR(255), \
        RECORDED_AT %s NOT NULL)"""
        .formatted(tableName, timestampType);

  }

}
