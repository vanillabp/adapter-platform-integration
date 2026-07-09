package io.vanillabp.integration.runtime.outbox;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.spi.PhaseTwoDispatch;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the JDBC-based phase-two outbox (see
 * {@link JdbcPhaseTwoOutbox}) to the {@link PhaseTwoDispatch} method corresponding to
 * the entry's operation:
 * <ul>
 *   <li>right after a commit (triggered by {@link JdbcPhaseTwoOutbox}) and</li>
 *   <li>by a fixed-delay poller (crash recovery and retries, poll interval configured
 *       by <code>vanillabp.outbox.poll-interval</code>) started on
 *       {@link StartupEvent}.</li>
 * </ul>
 * The poller uses a plain scheduled executor, so the <code>quarkus-scheduler</code>
 * extension is not required. Due entries are claimed atomically (optimistic update
 * incrementing the number of attempts and setting the next attempt according to
 * <code>vanillabp.outbox.attempt-frequency</code>), so a failed dispatch is
 * automatically retried with a backoff and multiple instances do not dispatch the same
 * entry concurrently. Entries are removed on successful dispatch only (at-least-once
 * semantics). After <code>vanillabp.outbox.block-after-attempts</code> failed attempts
 * an entry is blocked and has to be cleaned up manually.
 * <p>
 * The outbox table is created on startup (<code>CREATE TABLE IF NOT EXISTS</code>)
 * unless <code>vanillabp.outbox.create-schema</code> is disabled for manually managed
 * schemas.
 */
@ApplicationScoped
@Slf4j
public class JdbcPhaseTwoOutboxDispatcher {

  private static final String CREATE_TABLE = """
      CREATE TABLE IF NOT EXISTS %s (\
      ID VARCHAR(36) PRIMARY KEY, \
      WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
      BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
      OPERATION VARCHAR(255) NOT NULL, \
      AGGREGATE_ID VARCHAR(1024), \
      AGGREGATE_ID_TYPE VARCHAR(255), \
      CREATED_AT TIMESTAMP NOT NULL, \
      ATTEMPTS INT NOT NULL, \
      NEXT_ATTEMPT_AT TIMESTAMP NOT NULL)"""
      .formatted(JdbcPhaseTwoOutbox.TABLE_NAME);

  private static final String SELECT_DUE_ENTRIES = """
      SELECT ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, AGGREGATE_ID_TYPE, ATTEMPTS \
      FROM %s \
      WHERE NEXT_ATTEMPT_AT <= ? AND ATTEMPTS < ?"""
      .formatted(JdbcPhaseTwoOutbox.TABLE_NAME);

  private static final String CLAIM_ENTRY = """
      UPDATE %s \
      SET ATTEMPTS = ATTEMPTS + 1, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND ATTEMPTS = ?"""
      .formatted(JdbcPhaseTwoOutbox.TABLE_NAME);

  private static final String DELETE_ENTRY = """
      DELETE FROM %s \
      WHERE ID = ?"""
      .formatted(JdbcPhaseTwoOutbox.TABLE_NAME);

  /**
   * A due outbox entry read from the database.
   *
   * @param id The entry's ID
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param operation The scheduled operation (see the <code>OPERATION_*</code> constants of {@link JdbcPhaseTwoOutbox})
   * @param aggregateId The workflow aggregate's ID as a string
   * @param aggregateIdType The original type of the workflow aggregate's ID
   * @param attempts The number of dispatch attempts so far
   */
  private record Entry(
                       String id,
                       String workflowModuleId,
                       String bpmnProcessId,
                       String operation,
                       String aggregateId,
                       String aggregateIdType,
                       int attempts) {
  }

  @Inject
  Instance<DataSource> dataSource;

  @Inject
  Instance<PhaseTwoDispatch> phaseTwoDispatch;

  private QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties properties;

  private ScheduledExecutorService executor;

  /**
   * Creates the outbox table (unless disabled) and starts the fixed-delay poller. The
   * first run is executed immediately, dispatching committed-but-unprocessed entries
   * of a previously crashed instance.
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes final StartupEvent event) {

    if (!dataSource.isResolvable()) {
      log.debug("No datasource available - the JDBC-based phase-two outbox stays inactive");
      return;
    }

    properties = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(QuarkusMigrationAdapterProperties.class)
        .outbox();

    if (properties.createSchema()) {
      createTableIfNotExists();
    }

    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-outbox");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(
        this::poll,
        0,
        properties.pollInterval().toMillis(),
        TimeUnit.MILLISECONDS);

  }

  @PreDestroy
  void shutdown() {

    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }

  }

  /**
   * Runs a single poll asynchronously (used right after a commit).
   */
  public void triggerPoll() {

    if (executor != null) {
      executor.execute(this::poll);
    }

  }

  private void createTableIfNotExists() {

    try (var connection = dataSource.get().getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate(CREATE_TABLE);
    } catch (SQLException e) {
      throw new IllegalStateException(
          ("Could not create the phase-two outbox table '%s'! Set 'vanillabp.outbox.create-schema' "
              + "to 'false' and manage the schema manually if the DDL is not suitable for your database.")
              .formatted(JdbcPhaseTwoOutbox.TABLE_NAME), e);
    }

  }

  /**
   * Claims and dispatches all due entries. Exceptions are caught to keep the poller
   * alive.
   */
  private synchronized void poll() {

    try (var connection = dataSource.get().getConnection()) {
      for (final var entry : loadDueEntries(connection)) {
        if (claim(connection, entry)) {
          dispatch(connection, entry);
        }
      }
    } catch (Exception e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  private List<Entry> loadDueEntries(
      final Connection connection) throws SQLException {

    final var entries = new ArrayList<Entry>();
    try (var statement = connection.prepareStatement(SELECT_DUE_ENTRIES)) {
      statement.setTimestamp(1, Timestamp.from(Instant.now()));
      statement.setInt(2, properties.blockAfterAttempts());
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          entries.add(new Entry(
              resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet
                  .getString(5), resultSet.getString(6), resultSet.getInt(7)));
        }
      }
    }
    return entries;

  }

  /**
   * Claims an entry using an optimistic update: incrementing the number of attempts
   * and setting the backoff makes concurrent pollers (or other instances) skip the
   * entry, and a failed dispatch is retried automatically once the backoff elapsed.
   *
   * @param connection The connection to be used
   * @param entry The entry to be claimed
   * @return Whether the entry was claimed by this poller
   */
  private boolean claim(
      final Connection connection,
      final Entry entry) throws SQLException {

    try (var statement = connection.prepareStatement(CLAIM_ENTRY)) {
      statement.setTimestamp(1, Timestamp.from(Instant.now().plus(properties.attemptFrequency())));
      statement.setString(2, entry.id());
      statement.setInt(3, entry.attempts());
      return statement.executeUpdate() == 1;
    }

  }

  /**
   * Dispatches a single claimed entry to the {@link PhaseTwoDispatch} method
   * corresponding to the entry's operation. On success the entry is removed; on
   * failure it stays claimed and is retried after the configured backoff.
   *
   * @param connection The connection used to remove the entry on success
   * @param entry The claimed entry
   */
  private void dispatch(
      final Connection connection,
      final Entry entry) throws SQLException {

    try {
      switch (entry.operation()) {
        case JdbcPhaseTwoOutbox.OPERATION_START_WORKFLOW -> phaseTwoDispatch
            .get()
            .startWorkflowPhaseTwo(
                entry.workflowModuleId(),
                entry.bpmnProcessId(),
                convertAggregateId(entry));
        default -> throw new IllegalStateException(
            "Unknown operation '%s' of outbox entry '%s'! Maybe it was written by a newer version of your software?"
                .formatted(entry.operation(), entry.id()));
      }
    } catch (Exception e) {
      if (entry.attempts() + 1 >= properties.blockAfterAttempts()) {
        log.error(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
            entry.operation(),
            entry.bpmnProcessId(),
            entry.workflowModuleId(),
            entry.aggregateId(),
            entry.attempts() + 1,
            entry.id(),
            e);
      } else {
        log.warn(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed - will retry",
            entry.operation(),
            entry.bpmnProcessId(),
            entry.workflowModuleId(),
            entry.aggregateId(),
            e);
      }
      return;
    }
    try (var statement = connection.prepareStatement(DELETE_ENTRY)) {
      statement.setString(1, entry.id());
      statement.executeUpdate();
    }

  }

  /**
   * Converts the serialized aggregate ID back to its original type stored along the
   * entry. Unknown types are passed through as strings.
   *
   * @param entry The entry to be dispatched
   * @return The aggregate ID to be passed on
   */
  private Object convertAggregateId(
      final Entry entry) {

    final var aggregateId = entry.aggregateId();
    final var aggregateIdType = entry.aggregateIdType();
    if (aggregateId == null || aggregateIdType == null) {
      return aggregateId;
    }
    try {
      return switch (aggregateIdType) {
        case "java.lang.String" -> aggregateId;
        case "java.lang.Long" -> Long.valueOf(aggregateId);
        case "java.lang.Integer" -> Integer.valueOf(aggregateId);
        case "java.lang.Short" -> Short.valueOf(aggregateId);
        case "java.lang.Byte" -> Byte.valueOf(aggregateId);
        case "java.lang.Double" -> Double.valueOf(aggregateId);
        case "java.lang.Float" -> Float.valueOf(aggregateId);
        case "java.lang.Boolean" -> Boolean.valueOf(aggregateId);
        case "java.math.BigInteger" -> new BigInteger(aggregateId);
        case "java.math.BigDecimal" -> new BigDecimal(aggregateId);
        case "java.util.UUID" -> UUID.fromString(aggregateId);
        default -> {
          log.warn(
              "Unknown workflow-aggregate ID type '{}' of outbox entry '{}' - passing the ID through as a string!",
              aggregateIdType,
              entry.id());
          yield aggregateId;
        }
      };
    } catch (IllegalArgumentException e) {
      log.warn(
          "Could not convert workflow-aggregate ID '{}' of outbox entry '{}' to type '{}' "
              + "- passing the ID through as a string!",
          aggregateId,
          entry.id(),
          aggregateIdType,
          e);
      return aggregateId;
    }

  }

}
