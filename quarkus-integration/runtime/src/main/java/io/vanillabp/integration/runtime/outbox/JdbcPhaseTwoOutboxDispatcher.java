package io.vanillabp.integration.runtime.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the JDBC-based phase-two outbox
 * (see {@link JdbcPhaseTwoOutbox}) through the core's {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link JdbcPhaseTwoOutbox}) and</li>
 * <li>by a fixed-delay poller (crash recovery and retries, poll interval configured
 * by <code>vanillabp.outbox.poll-interval</code>) started on
 * {@link StartupEvent}.</li>
 * </ul>
 * The poller uses a plain scheduled executor, so the <code>quarkus-scheduler</code>
 * extension is not required. Due entries (status {@link #STATUS_OPEN}) are claimed
 * atomically (optimistic update incrementing the number of attempts and setting the
 * next attempt according to <code>vanillabp.outbox.attempt-frequency</code>), so a
 * failed dispatch is automatically retried with a backoff and multiple instances do
 * not dispatch the same entry concurrently. On successful dispatch the entry is
 * marked {@link #STATUS_DONE} - it stays in the table (keeping the deduplication
 * window open) and is deleted asynchronously once
 * <code>vanillabp.outbox.retention</code> passed. After
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts an entry is
 * marked {@link #STATUS_BLOCKED} and has to be cleaned up manually.
 * <p>
 * <strong>Cluster safety:</strong> multiple application instances (pods) may poll
 * concurrently without any distributed lock: the SELECT may return the same due
 * entries on several instances, but the claim is an optimistic UPDATE
 * (<code>WHERE ID = ? AND ATTEMPTS = ?</code>) - exactly one instance wins the
 * claim and dispatches the entry, the others simply skip it. The retention cleanup
 * is a plain idempotent DELETE.
 * <p>
 * The outbox table is created on startup unless
 * <code>vanillabp.outbox.create-schema</code> is disabled for manually managed
 * schemas - in that case also create the unique constraint on
 * <code>IDEMPOTENCY_KEY</code> yourself (the storage-level deduplication of the
 * outbox contract). The DDL is kept portable: table existence is checked via JDBC
 * metadata (<code>CREATE TABLE IF NOT EXISTS</code> is not supported by Oracle and
 * SQL Server), the timestamp type is chosen per database (SQL Server's
 * <code>TIMESTAMP</code> is a row version, MySQL's has auto-initialization quirks
 * and a 2038 range limit) and the idempotency key is limited to 512 characters so
 * MySQL's unique-index key-length limit (3072 bytes with utf8mb4) is respected.
 */
@ApplicationScoped
@Slf4j
public class JdbcPhaseTwoOutboxDispatcher {

  public static final String STATUS_OPEN = "OPEN";

  public static final String STATUS_DONE = "DONE";

  public static final String STATUS_BLOCKED = "BLOCKED";

  private static final String SELECT_DUE_ENTRIES = """
      SELECT ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, ATTEMPTS \
      FROM %s \
      WHERE STATUS = '%s' AND NEXT_ATTEMPT_AT <= ? AND ATTEMPTS < ?""";

  private static final String CLAIM_ENTRY = """
      UPDATE %s \
      SET ATTEMPTS = ATTEMPTS + 1, NEXT_ATTEMPT_AT = ? \
      WHERE ID = ? AND ATTEMPTS = ?""";

  private static final String MARK_ENTRY_DONE = """
      UPDATE %s \
      SET STATUS = '%s', DONE_AT = ? \
      WHERE ID = ?""";

  private static final String MARK_ENTRY_BLOCKED = """
      UPDATE %s \
      SET STATUS = '%s' \
      WHERE ID = ?""";

  private static final String DELETE_EXPIRED_DONE_ENTRIES = """
      DELETE FROM %s \
      WHERE STATUS = '%s' AND DONE_AT < ?""";

  private String tableName;

  private String selectDueEntries;

  private String claimEntry;

  private String markEntryDone;

  private String markEntryBlocked;

  private String deleteExpiredDoneEntries;

  /**
   * A due outbox entry read from the database.
   *
   * @param id The entry's ID
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param operation The name of the scheduled {@link PhaseTwoOperation}
   * @param aggregateId The workflow aggregate's ID in serialized form
   * @param adapterId The ID of the elected BPMS adapter (may be <code>null</code>)
   * @param attempts The number of dispatch attempts so far
   */
  private record Entry(
                       String id,
                       String workflowModuleId,
                       String bpmnProcessId,
                       String operation,
                       String aggregateId,
                       String adapterId,
                       String serializedArgs,
                       int attempts) {
  }

  @Inject
  Instance<DataSource> dataSource;

  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  private volatile PhaseTwoOutboxProperties properties;

  private ScheduledExecutorService executor;

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily so
   * {@link JdbcPhaseTwoOutbox} can resolve its table name even before the startup
   * event was observed.
   *
   * @return The outbox configuration
   */
  PhaseTwoOutboxProperties getProperties() {

    if (properties == null) {
      properties = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
          ConfigProvider
              .getConfig()
              .unwrap(SmallRyeConfig.class)
              .getConfigMapping(QuarkusMigrationAdapterProperties.class)
              .outbox());
    }
    return properties;

  }

  /**
   * Creates the outbox table (unless disabled) and starts the fixed-delay poller. The
   * first run is executed immediately, dispatching committed-but-unprocessed entries
   * of a previously crashed instance. The observer priority guarantees that the
   * deployment pipeline deployed the BPMN resources and started workflow processing
   * BEFORE any recovered entry is dispatched (see
   * {@link VanillaBpDeploymentRunner#OUTBOX_DISPATCHER_STARTUP_PRIORITY}).
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes
      @Priority(VanillaBpDeploymentRunner.OUTBOX_DISPATCHER_STARTUP_PRIORITY) final StartupEvent event) {

    if (!dataSource.isResolvable()) {
      log.debug("No datasource available - the JDBC-based phase-two outbox stays inactive");
      return;
    }

    getProperties();
    if (!properties.getJdbc().isEnabled()) {
      log.debug("'vanillabp.outbox.jdbc.enabled' is false - the JDBC-based phase-two outbox stays inactive");
      return;
    }

    tableName = JdbcPhaseTwoOutbox.tableName(properties);
    selectDueEntries = SELECT_DUE_ENTRIES.formatted(tableName, STATUS_OPEN);
    claimEntry = CLAIM_ENTRY.formatted(tableName);
    markEntryDone = MARK_ENTRY_DONE.formatted(tableName, STATUS_DONE);
    markEntryBlocked = MARK_ENTRY_BLOCKED.formatted(tableName, STATUS_BLOCKED);
    deleteExpiredDoneEntries = DELETE_EXPIRED_DONE_ENTRIES.formatted(tableName, STATUS_DONE);

    if (properties.isCreateSchema()) {
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
        properties.getPollInterval().toMillis(),
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

    try (var connection = dataSource.get().getConnection()) {
      // existence is checked via JDBC metadata since 'CREATE TABLE IF NOT EXISTS'
      // is not supported by all databases (e.g. Oracle, SQL Server)
      if (tableExists(connection, tableName)) {
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(buildCreateTable(connection, tableName));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          """
              Could not create the phase-two outbox table '%s'! Set 'vanillabp.outbox.create-schema' \
              to 'false' and manage the schema manually if the DDL is not suitable for your database."""
              .formatted(tableName), e);
    }

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
   * Builds the CREATE TABLE statement using a timestamp type suitable for the
   * database: SQL Server's <code>TIMESTAMP</code> is a row version (not a
   * date-time), MySQL's <code>TIMESTAMP</code> has auto-initialization quirks and
   * ends in 2038. The idempotency key is limited to 512 characters (2048 bytes
   * with utf8mb4) to stay below MySQL's unique-index key-length limit of 3072
   * bytes.
   *
   * @param connection The connection used to detect the database
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
        ID VARCHAR(36) PRIMARY KEY, \
        WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
        BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
        OPERATION VARCHAR(255) NOT NULL, \
        AGGREGATE_ID VARCHAR(1024), \
        ADAPTER_ID VARCHAR(255), \
        ARGS VARCHAR(2048), \
        IDEMPOTENCY_KEY VARCHAR(512) UNIQUE, \
        STATUS VARCHAR(16) NOT NULL, \
        CREATED_AT %s NOT NULL, \
        ATTEMPTS INT NOT NULL, \
        NEXT_ATTEMPT_AT %s NOT NULL, \
        DONE_AT %s)"""
        .formatted(
            tableName,
            timestampType,
            timestampType,
            timestampType);

  }

  /**
   * Claims and dispatches all due entries, then deletes DONE entries whose retention
   * passed. Exceptions are caught to keep the poller alive.
   */
  private synchronized void poll() {

    try (var connection = dataSource.get().getConnection()) {
      for (final var entry : loadDueEntries(connection)) {
        if (claim(connection, entry)) {
          dispatch(connection, entry);
        }
      }
      cleanupDoneEntries(connection);
    } catch (Exception e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  private List<Entry> loadDueEntries(
      final Connection connection) throws SQLException {

    final var entries = new ArrayList<Entry>();
    try (var statement = connection.prepareStatement(selectDueEntries)) {
      statement.setTimestamp(1, Timestamp.from(Instant.now()));
      statement.setInt(2, properties.getBlockAfterAttempts());
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          entries.add(new Entry(
              resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet
                  .getString(5), resultSet.getString(6), resultSet.getString(7), resultSet.getInt(8)));
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

    try (var statement = connection.prepareStatement(claimEntry)) {
      statement.setTimestamp(1, Timestamp.from(Instant.now().plus(properties.getAttemptFrequency())));
      statement.setString(2, entry.id());
      statement.setInt(3, entry.attempts());
      return statement.executeUpdate() == 1;
    }

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   *
   * @param connection The connection used to update the entry
   * @param entry The claimed entry
   */
  private void dispatch(
      final Connection connection,
      final Entry entry) throws SQLException {

    try {
      final PhaseTwoOperation operation;
      try {
        operation = PhaseTwoOperation.valueOf(entry.operation());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            "Unknown operation '%s' of outbox entry '%s'! Maybe it was written by a newer version of your software?"
                .formatted(entry.operation(), entry.id()));
      }
      // entry.attempts() holds the count BEFORE this claim - a value > 0 means the
      // entry was dispatched before (recovered/retried): the router then runs the
      // START re-dispatch mitigation
      phaseTwoRouter
          .get()
          .dispatch(
              new PhaseTwoCall(
                  operation, entry.workflowModuleId(), entry.bpmnProcessId(), entry.aggregateId(), entry
                      .adapterId(), PhaseTwoCall.deserializeArgs(entry.serializedArgs())),
              entry.attempts() > 0);
    } catch (Exception e) {
      if (entry.attempts() + 1 >= properties.getBlockAfterAttempts()) {
        try (var statement = connection.prepareStatement(markEntryBlocked)) {
          statement.setString(1, entry.id());
          statement.executeUpdate();
        }
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
    try (var statement = connection.prepareStatement(markEntryDone)) {
      statement.setTimestamp(1, Timestamp.from(Instant.now()));
      statement.setString(2, entry.id());
      statement.executeUpdate();
    }

  }

  /**
   * Deletes successfully dispatched (DONE) entries whose retention period passed -
   * the asynchronous cleanup of the "DONE instead of delete" contract.
   *
   * @param connection The connection to be used
   */
  private void cleanupDoneEntries(
      final Connection connection) throws SQLException {

    try (var statement = connection.prepareStatement(deleteExpiredDoneEntries)) {
      statement.setTimestamp(1, Timestamp.from(Instant.now().minus(properties.getRetention())));
      statement.executeUpdate();
    }

  }

}
