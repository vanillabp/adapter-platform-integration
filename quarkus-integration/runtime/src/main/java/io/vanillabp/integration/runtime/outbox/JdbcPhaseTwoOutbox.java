package io.vanillabp.integration.runtime.outbox;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * The default {@link PhaseTwoOutbox} implementation for Quarkus (own code - gruelbox
 * does not support JTA): the outbox entry is written into the table
 * {@link #TABLE_NAME} using a JDBC connection of the Agroal data source. Since the
 * connection is acquired within the still-running JTA transaction, it is enlisted
 * automatically - the entry becomes visible if and only if the transaction commits.
 * <p>
 * The scheduled operation is stored as a discriminator with each entry (see
 * {@link #OPERATION_START_WORKFLOW}), so the {@link JdbcPhaseTwoOutboxDispatcher}
 * calls the corresponding
 * {@link io.vanillabp.integration.adapter.spi.PhaseTwoDispatch} method. The workflow
 * aggregate's ID is stored in serialized form (as a string) together with its original
 * type, so the dispatcher can convert it back before dispatching.
 */
@ApplicationScoped
public class JdbcPhaseTwoOutbox implements PhaseTwoOutbox {

  /**
   * The table used to store outbox entries.
   */
  public static final String TABLE_NAME = "VANILLABP_PHASE_TWO_OUTBOX";

  /**
   * Operation discriminator of entries scheduled via
   * {@link #scheduleStartWorkflow(String, String, Object)}.
   */
  public static final String OPERATION_START_WORKFLOW = "START_WORKFLOW";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, AGGREGATE_ID_TYPE, \
      CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)"""
      .formatted(TABLE_NAME);

  @Inject
  Instance<DataSource> dataSource;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  @Inject
  JdbcPhaseTwoOutboxDispatcher dispatcher;

  @Override
  public void scheduleStartWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    if (txRegistry.getTransactionKey() == null) {
      throw new IllegalStateException(
          "No transaction active! The phase-two outbox has to be used within the still-running "
              + "transaction persisting the workflow aggregate.");
    }
    if (!dataSource.isResolvable()) {
      throw new IllegalStateException(
          "No datasource available! The JDBC-based phase-two outbox requires a configured "
              + "default datasource (quarkus-agroal).");
    }

    final var now = Instant.now();
    try (var connection = dataSource.get().getConnection(); var statement = connection.prepareStatement(INSERT_ENTRY)) {
      statement.setString(1, UUID.randomUUID().toString());
      statement.setString(2, workflowModuleId);
      statement.setString(3, bpmnProcessId);
      statement.setString(4, OPERATION_START_WORKFLOW);
      statement.setString(5, workflowAggregateId == null ? null : workflowAggregateId.toString());
      statement.setString(6, workflowAggregateId == null ? null : workflowAggregateId.getClass().getName());
      statement.setTimestamp(7, Timestamp.from(now));
      statement.setTimestamp(8, Timestamp.from(now));
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(
          "Could not write the phase-two outbox entry for BPMN process '%s' of workflow module '%s'!"
              .formatted(bpmnProcessId, workflowModuleId), e);
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

  }

}
