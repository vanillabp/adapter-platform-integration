package io.vanillabp.integration.runtime.outbox;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import io.vanillabp.integration.runtime.processservice.PlatformDefaultStore;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Quarkus (own code - gruelbox
 * does not support JTA): the outbox entry is written into the configured table
 * ({@code vanillabp.outbox.jdbc.table}, default {@link #DEFAULT_TABLE_NAME}) using a
 * JDBC connection of the Agroal data source. Since the
 * connection is acquired within the still-running JTA transaction, it is enlisted
 * automatically - the entry becomes visible if and only if the transaction commits.
 * <p>
 * The entry persists the fields of the {@link PhaseTwoCall} including the operation
 * discriminator and the elected adapter ID; the workflow-aggregate ID is stored in
 * its serialized (String) form only - conversion back to the aggregate's ID type
 * happens in the core's router at dispatch time. Deduplication is enforced by a
 * unique constraint on the entry's idempotency key - a duplicate schedule raises a
 * constraint violation which is turned into the contract's no-op
 * (<code>false</code>).
 * <p>
 * The {@link PhaseTwoCall#args()} map is persisted GENERICALLY in its serialized
 * form ({@link PhaseTwoCall#serializeArgs(java.util.Map)}, column
 * <code>ARGS</code>) - the store stays operation-agnostic (stores never interpret
 * arguments; only the core's router does).
 */
@ApplicationScoped
@Slf4j
public class JdbcPhaseTwoOutbox implements PhaseTwoOutbox, PlatformDefaultStore {

  /**
   * The default name of the table used to store outbox entries (override via
   * <code>vanillabp.outbox.jdbc.table</code> - every outbox instance needs its own
   * table, two dispatchers polling the same table would compete and
   * double-dispatch).
   */
  public static final String DEFAULT_TABLE_NAME = "VANILLABP_PHASE_TWO_OUTBOX";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, '%s', ?, 0, ?)""";

  /**
   * Resolves the configured table name (<code>vanillabp.outbox.jdbc.table</code>,
   * falling back to {@link #DEFAULT_TABLE_NAME}).
   *
   * @param properties The outbox configuration
   * @return The table name
   */
  static String tableName(
      final io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties properties) {

    final var table = properties
        .getJdbc()
        .getTable();
    return table == null ? DEFAULT_TABLE_NAME : table;

  }

  @Inject
  Instance<DataSource> dataSource;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  @Inject
  JdbcPhaseTwoOutboxDispatcher dispatcher;

  @Override
  public QuarkusPersistenceTechnology.Technology technology() {

    return QuarkusPersistenceTechnology.Technology.JPA;

  }

  /**
   * Whether this default outbox is usable: the extension registers the bean at
   * build time, but without a configured datasource it cannot store anything - an
   * unusable default must not be selected for an aggregate (the startup validation
   * then reports "no outbox available" with the remedies instead of failing at the
   * first workflow start).
   *
   * @return Whether a datasource is available
   */
  @Override
  public boolean isAvailable() {

    return dataSource.isResolvable();

  }

  /**
   * The adapter ids the OPEN entries of one BPMN process are waiting for: an
   * id which is not configured any more means that it was renamed or removed too early,
   * and both leave the workflow of a START entry unstarted.
   */
  @Override
  public java.util.Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (!dataSource.isResolvable()) {
      return java.util.Set.of();
    }
    final var tableName = tableName(dispatcher.getProperties());
    final var selectAdapterIds = """
        SELECT DISTINCT ADAPTER_ID FROM %s \
        WHERE WORKFLOW_MODULE_ID = ? AND BPMN_PROCESS_ID = ? AND STATUS = ? AND ADAPTER_ID IS NOT NULL"""
        .formatted(tableName);
    try (var connection = dataSource.get().getConnection(); var statement = connection
        .prepareStatement(selectAdapterIds)) {
      statement.setString(1, workflowModuleId);
      statement.setString(2, bpmnProcessId);
      statement.setString(3, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
      try (var resultSet = statement.executeQuery()) {
        final var adapterIds = new java.util.LinkedHashSet<String>();
        while (resultSet.next()) {
          adapterIds.add(resultSet.getString(1));
        }
        return adapterIds;
      }
    } catch (final java.sql.SQLException e) {
      // a startup diagnosis must not keep an application from booting: unanswered is
      // what a store which cannot tell answers anyway
      log
          .debug(
              "Could not read the adapter ids of open outbox entries of BPMN process '{}' "
                  + "(workflow module '{}') from table '{}'",
              bpmnProcessId,
              workflowModuleId,
              tableName,
              e);
      return java.util.Set.of();
    }

  }

  /**
   * Counts the entries waiting for their dispatch. A single indexed count over the
   * outbox table, which holds what did not run yet plus what is kept until the
   * retention passes - the same table the dispatcher polls.
   */
  @Override
  public java.util.OptionalLong pendingCalls() {

    if (!dataSource.isResolvable()) {
      return java.util.OptionalLong.empty();
    }
    final var countPending = "SELECT COUNT(*) FROM %s WHERE STATUS = ?"
        .formatted(tableName(dispatcher.getProperties()));
    try (var connection = dataSource.get().getConnection(); var statement = connection.prepareStatement(countPending)) {
      statement.setString(1, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? java.util.OptionalLong.of(resultSet.getLong(1))
            : java.util.OptionalLong.empty();
      }
    } catch (final SQLException e) {
      // a metric must never be the reason an application fails - the gauge reports
      // nothing for this collection and the next one tries again
      log.debug("Could not count the pending entries of the JDBC phase-two outbox", e);
      return java.util.OptionalLong.empty();
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    if (txRegistry.getTransactionKey() == null) {
      throw new IllegalStateException(
          """
              No transaction active! The phase-two outbox has to be used within the still-running \
              transaction persisting the workflow aggregate.""");
    }
    if (!dataSource.isResolvable()) {
      throw new IllegalStateException(
          """
              No datasource available! The JDBC-based phase-two outbox requires a configured \
              default datasource (quarkus-agroal).""");
    }

    final var now = Instant.now();
    final var insertEntry = INSERT_ENTRY
        .formatted(
            tableName(dispatcher.getProperties()),
            JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN);
    try (var connection = dataSource.get().getConnection(); var statement = connection.prepareStatement(insertEntry)) {
      statement.setString(1, UUID.randomUUID().toString());
      statement.setString(2, call.workflowModuleId());
      statement.setString(3, call.bpmnProcessId());
      statement.setString(4, call.operation());
      statement.setString(5, call.workflowAggregateId());
      statement.setString(6, call.adapterId());
      statement.setString(7, PhaseTwoCall.serializeArgs(call.args()));
      statement.setString(8, call.idempotencyKey().orElse(null));
      statement.setTimestamp(9, Timestamp.from(now));
      statement.setTimestamp(10, Timestamp.from(now));
      statement.executeUpdate();
    } catch (SQLException e) {
      // the idempotency key is already present, so the call was scheduled before -
      // the contract's no-op
      if (isDuplicateKey(e)) {
        log.debug(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "was already scheduled - skipping",
            call.operation(),
            call.bpmnProcessId(),
            call.workflowModuleId(),
            call.workflowAggregateId());
        return false;
      }
      throw new RuntimeException(
          "Could not write the phase-two outbox entry for BPMN process '%s' of workflow module '%s'!"
              .formatted(call.bpmnProcessId(), call.workflowModuleId()), e);
    }

    // dispatch the entry right after the transaction was committed; recovery after a
    // crash is covered by the dispatcher's fixed-delay poller
    txRegistry.registerInterposedSynchronization(new Synchronization() {
      @Override
      public void beforeCompletion() {
        // nothing to do
      }

      @Override
      public void afterCompletion(
          final int status) {
        if (status == Status.STATUS_COMMITTED) {
          dispatcher.triggerPoll();
        }
      }
    });

    return true;

  }


  /**
   * Whether the given exception signals a violated unique constraint (= the
   * idempotency key already exists).
   *
   * @param e The exception raised by the insert
   * @return Whether the insert failed due to a duplicate key
   */
  private static boolean isDuplicateKey(
      final SQLException e) {

    // PostgreSQL's JDBC driver does not map unique violations to the dedicated
    // subclass - fall back to the standard SQL state class 23 (integrity
    // constraint violation) for such drivers
    return (e instanceof SQLIntegrityConstraintViolationException) || ((e.getSQLState() != null) && e.getSQLState()
        .startsWith("23"));

  }

}
