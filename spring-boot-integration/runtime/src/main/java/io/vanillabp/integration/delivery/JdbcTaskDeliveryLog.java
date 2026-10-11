package io.vanillabp.integration.delivery;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * The default {@link TaskDeliveryLog} for Spring Boot applications which persist their
 * workflow aggregates with JPA or JDBC. The records are written through a connection
 * which {@link DataSourceUtils} binds to the Spring-managed transaction. So a record and
 * the aggregate changes of the same delivery commit together. That is the recording
 * contract of {@link TaskDeliveryLog}.
 * <p>
 * Unlike the phase-two outbox, this does NOT use gruelbox. Gruelbox stores calls which
 * are still to be dispatched. A delivery record is a fact which is read back. The table
 * ({@link JdbcTaskDeliveryStore#DEFAULT_TABLE_NAME}) is VanillaBP's own. It is created at
 * startup unless <code>vanillabp.outbox.create-schema</code> is disabled.
 */
public class JdbcTaskDeliveryLog implements TaskDeliveryLog, JdbcConnectionAccess {

  private final DataSource dataSource;

  private final JdbcTaskDeliveryStore store;

  private final Duration retention;

  private final Duration workflowStartRetention;

  private final io.vanillabp.integration.spi.WorkflowStartSieve workflowStartSieve;

  private final TaskDeliveryRetentionCleanup retentionCleanup;

  /**
   * Builds the log, and nothing more. The table and the cleanup wait for
   * {@link #start(boolean)}, because the DDL must not run while the beans are still being
   * built.
   *
   * @param dataSource Where the table lives. It is the database the workflow aggregates are
   *          persisted in. That lets a record and the aggregate commit together
   * @param tableName The table the records go into
   * @param retention How long a record is kept. In this window, a redelivery is answered
   *          from the record and the <code>@WorkflowTask</code> method does not run again.
   *          That is why it is a setting of its own, see decision 24 in the repository's
   *          DECISIONS.md
   * @param workflowStartRetention How long the row about a started workflow is kept,
   *          counted from the start. It is a period of its own. That row is read as long as
   *          somebody may ask which workflow an aggregate belongs to, and that outlasts the
   *          workflow
   * @param workflowStartSieve Decides per row whether it may really go once that period
   *          passed. <code>null</code> where the application did not ask for the second
   *          sieve
   */
  public JdbcTaskDeliveryLog(
      final DataSource dataSource,
      final String tableName,
      final Duration retention,
      final Duration workflowStartRetention,
      final io.vanillabp.integration.spi.WorkflowStartSieve workflowStartSieve) {

    this.dataSource = dataSource;
    this.store = new JdbcTaskDeliveryStore(this, tableName);
    this.retention = retention;
    this.workflowStartRetention = workflowStartRetention;
    this.workflowStartSieve = workflowStartSieve;
    this.retentionCleanup = new TaskDeliveryRetentionCleanup(
        tableName, retention, workflowStartRetention, this::cleanUpExpiredRecords);

  }

  /**
   * Creates the table and starts the retention cleanup.
   *
   * @param createSchema Whether VanillaBP creates the table itself
   */
  public void start(
      final boolean createSchema) {

    if (createSchema) {
      store.createSchemaIfNotExists();
    } else {
      // the application creates its schema itself. Then a missing table means the
      // deployment forgot to apply the migration, and the startup says so
      store.validateSchemaExists();
    }
    retentionCleanup.start();

  }

  /**
   * Refreshes the records of the open tasks which were redelivered since the last run. Then
   * it deletes the records whose retention period passed. Then it deletes the rows about
   * started workflows whose own period passed. The background cleanup runs it, and it can
   * be called on demand, e.g. by tests. All of it is the store's business and happens in
   * that order.
   *
   * @return The number of rows deleted
   */
  public int cleanUpExpiredRecords() {

    final var expiredDeliveries = store.deleteExpired(retention);
    if ((workflowStartRetention == null) || workflowStartRetention.isZero()) {
      // a zero period says the rows about started workflows are kept for good. An
      // application whose aggregates live forever asks for that
      return expiredDeliveries;
    }
    return expiredDeliveries + store.deleteExpiredWorkflowStarts(workflowStartRetention, workflowStartSieve);

  }

  @Override
  public void stillOpen(
      final String deliveryKey) {

    retentionCleanup.aDeliveryWasRecorded();
    store.stillOpen(deliveryKey);

  }

  /**
   * Stops the retention cleanup. It is called on shutdown of the application context.
   */
  public void stop() {

    retentionCleanup.stop();

  }

  @Override
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    return store.recordedDelivery(deliveryKey);

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          """
              No transaction active! A task delivery has to be recorded within the still-running \
              transaction persisting the workflow aggregate - a record committed on its own would \
              skip the @WorkflowTask method of a redelivery although nothing was persisted.""");
    }
    // this gives the hourly cleanup something to do. A store which nobody wrote to has
    // nothing left to delete that an earlier run did not delete already
    retentionCleanup.aDeliveryWasRecorded();
    return store.record(delivery);

  }

  @Override
  public boolean recordWorkflowStart(
      final TaskDelivery workflowStart) {

    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          """
              No transaction active! The start of a workflow has to be written down within the \
              still-running transaction which persists the workflow aggregate respectively which \
              dispatches the start - a row committed on its own would name a workflow which was \
              never created.""");
    }
    retentionCleanup.aDeliveryWasRecorded();
    return store.recordWorkflowStart(workflowStart);

  }

  /**
   * The adapter ids which the OPEN records of one BPMN process belong to. The shared store
   * answers it with one query over the columns it indexes anyway.
   */
  @Override
  public Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return store.adapterIdsOfOpenTasks(workflowModuleId, bpmnProcessId);

  }

  @Override
  public Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return store.hasOpenRecords(workflowModuleId, bpmnProcessId);

  }

  /**
   * The record which left one task open. It is read within the caller's transaction,
   * through the connection bound to it. So it sees what that transaction has written
   * itself.
   */
  @Override
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return store.recordOfTask(workflowModuleId, bpmnProcessId, workflowAggregateId, taskId);

  }

  /**
   * The open tasks of one workflow aggregate. They are read within the caller's
   * transaction, through the connection bound to it.
   */
  @Override
  public List<TaskDelivery> openTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return store.openTasksOfAggregate(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

  /**
   * The open tasks of one workflow of the BPMS. They are read within the caller's
   * transaction, through the connection bound to it.
   */
  @Override
  public List<TaskDelivery> openTasksOfWorkflow(
      final String workflowModuleId,
      final String workflowId) {

    return store.openTasksOfWorkflow(workflowModuleId, workflowId);

  }

  @Override
  public int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return store.markTaskClosed(workflowModuleId, bpmnProcessId, workflowAggregateId, taskId);

  }

  @Override
  public int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Instant recordedBefore) {

    return store
        .deleteRecordsOf(workflowModuleId, bpmnProcessId, workflowAggregateId, recordedBefore);

  }

  @Override
  public Connection acquire() throws SQLException {

    // bound to the Spring-managed transaction if one is running, which the recording
    // contract requires. Otherwise a plain connection of the pool, used by reads
    return DataSourceUtils.getConnection(dataSource);

  }

  @Override
  public void release(
      final Connection connection) {

    DataSourceUtils.releaseConnection(connection, dataSource);

  }

}
