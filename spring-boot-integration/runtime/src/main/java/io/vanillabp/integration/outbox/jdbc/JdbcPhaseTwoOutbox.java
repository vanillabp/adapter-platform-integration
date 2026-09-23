package io.vanillabp.integration.outbox.jdbc;

import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.outbox.PhaseTwoOutboxTransaction;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.annotation.PreDestroy;

/**
 * The default {@link PhaseTwoOutbox} of a Spring Boot application whose workflow
 * aggregates live in a relational database. What it stores and how it dispatches is the
 * core's {@link JdbcPhaseTwoOutboxStore} with its {@link JdbcPhaseTwoOutboxDispatcher} -
 * the same code a Quarkus application runs. This bean is the Spring half of it: the
 * connection {@code DataSourceUtils} binds to the running transaction, the
 * synchronization which says that the transaction committed, and the
 * {@link ApplicationReadyEvent} the poller starts on.
 * <p>
 * The poller starts AFTER workflow processing did
 * ({@link SpringBootDeploymentService#OUTBOX_DISPATCHER_LISTENER_ORDER}), so nothing a
 * crashed instance left behind is carried to a BPMS which has not seen the models yet.
 * The tables are created before that, while this bean is built, because a workflow may
 * be started as soon as the application context is up.
 */
public class JdbcPhaseTwoOutbox implements PhaseTwoOutbox {

  private final JdbcPhaseTwoOutboxStore store;

  private final JdbcPhaseTwoOutboxDispatcher dispatcher;

  /**
   * The outbox on the table <code>vanillabp.outbox.jdbc.table</code> names, which is what
   * every application gets unless it builds a second outbox of its own.
   *
   * @param dataSource Where the outbox table lives - the database the workflow
   *          aggregates are persisted in
   * @param properties The bound <code>vanillabp.outbox</code> section
   * @param payloadStore Where the bytes of a call which carries a payload lie
   * @param phaseTwoRouter Provider of the router a claimed entry is handed to
   * @param metrics Provider of what a blocked entry is counted into; Micrometer is
   *          optional, so the bean may legitimately be absent
   */
  public JdbcPhaseTwoOutbox(
      final DataSource dataSource,
      final PhaseTwoOutboxProperties properties,
      final JdbcPhaseTwoPayloadStore payloadStore,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final ObjectProvider<VanillaBpMetrics> metrics) {

    this(
        dataSource, properties, JdbcPhaseTwoOutboxStore.tableName(properties), payloadStore, phaseTwoRouter, metrics);

  }

  /**
   * An outbox on a table of its own, which is how a high-load process gets a store
   * nothing else writes into. Everything else is the same, the settings included: two
   * dispatchers polling one table would compete and dispatch an entry twice, so the
   * table and the payload table have to be ones no other outbox uses.
   *
   * @param dataSource Where the tables live
   * @param properties The bound <code>vanillabp.outbox</code> section
   * @param tableName The table this outbox writes its entries into
   * @param payloadStore Where the bytes of a call which carries a payload lie
   * @param phaseTwoRouter Provider of the router a claimed entry is handed to
   * @param metrics Provider of what a blocked entry is counted into
   */
  public JdbcPhaseTwoOutbox(
      final DataSource dataSource,
      final PhaseTwoOutboxProperties properties,
      final String tableName,
      final JdbcPhaseTwoPayloadStore payloadStore,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final ObjectProvider<VanillaBpMetrics> metrics) {

    final var connections = connectionsOf(dataSource);
    this.dispatcher = new JdbcPhaseTwoOutboxDispatcher(
        connections, properties, tableName, payloadStore, phaseTwoRouter::getObject, () -> SpringBootMigrationAdapterAutoConfiguration
            .vanillaBpMetricsOf(metrics), JdbcPhaseTwoOutbox.class.getSimpleName());
    this.store = new JdbcPhaseTwoOutboxStore(
        connections, transaction(), tableName, payloadStore, dispatcher::triggerPoll);
    dispatcher.prepareSchema();

  }

  /**
   * The table this outbox writes its entries into. The auto-configuration asks, because the
   * message about entries left in the former store has to name the table this one uses.
   *
   * @return The table this outbox writes its entries into
   */
  public String getTableName() {

    return store.getTableName();

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    return store.schedule(call);

  }

  @Override
  public boolean scheduleReplacingWhatIsStillWaiting(
      final PhaseTwoCall call) {

    return store.scheduleReplacingWhatIsStillWaiting(call);

  }

  @Override
  public Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return store.adapterIdsOfPendingCalls(workflowModuleId, bpmnProcessId);

  }

  @Override
  public OptionalLong pendingCalls() {

    return store.pendingCalls();

  }

  @Override
  public Optional<Duration> ageOfOldestPendingCall() {

    return store.ageOfOldestPendingCall();

  }

  /**
   * Starts the poller once workflow processing runs (see
   * {@link SpringBootDeploymentService#OUTBOX_DISPATCHER_LISTENER_ORDER}).
   */
  @Order(SpringBootDeploymentService.OUTBOX_DISPATCHER_LISTENER_ORDER)
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    dispatcher.start();

  }

  /**
   * Stops the poller when the application context closes. An entry which was not dispatched
   * stays in the table, and the first poll of the next start carries it.
   */
  @PreDestroy
  public void stopPolling() {

    dispatcher.stop();

  }

  /**
   * The connection Spring binds to the transaction currently running, and a plain
   * connection of the pool where none runs - which is what the poller and the lanes of
   * the dispatcher get, because they work outside the application's transactions.
   * <p>
   * Public because an application building an outbox of its own needs the same thing for
   * the payload store it hands in.
   *
   * @param dataSource The data source the outbox table lives in
   * @return How this platform hands out connections
   */
  public static JdbcConnectionAccess connectionsOf(
      final DataSource dataSource) {

    return new JdbcConnectionAccess() {
      @Override
      public Connection acquire() {

        return DataSourceUtils.getConnection(dataSource);

      }

      @Override
      public void release(
          final Connection connection) {

        DataSourceUtils.releaseConnection(connection, dataSource);

      }
    };

  }

  /**
   * The Spring transaction of the caller, as the core store needs it.
   *
   * @return The transaction handle of this platform
   */
  private static PhaseTwoOutboxTransaction transaction() {

    return new PhaseTwoOutboxTransaction() {
      @Override
      public boolean isActive() {

        return TransactionSynchronizationManager.isActualTransactionActive();

      }

      @Override
      public void afterCommit(
          final Runnable action) {

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
          // a transaction Spring does not synchronize, which a store cannot be told
          // about. Running the action now costs one poll which may find nothing - the
          // entry is read from the table, so a rollback simply leaves nothing to read
          action.run();
          return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCommit() {

            action.run();

          }
        });

      }
    };

  }

}
