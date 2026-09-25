package io.vanillabp.integration.runtime.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.outbox.PhaseTwoOutboxTransaction;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.runtime.processservice.PhaseTwoRouterProducer;
import io.vanillabp.integration.runtime.processservice.PlatformDefaultStore;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} of a Quarkus application whose workflow aggregates
 * live in a relational database. What it stores and how it dispatches is the core's
 * {@link JdbcPhaseTwoOutboxStore} with its {@link JdbcPhaseTwoOutboxDispatcher} - the
 * same code a Spring Boot application runs. This bean is the Quarkus half of it: an
 * Agroal connection, which is enlisted in the running JTA transaction when it is
 * acquired, the {@link TransactionSynchronizationRegistry} for the commit, and the
 * {@link StartupEvent} the poller starts on.
 * <p>
 * The observer priority guarantees that the deployment pipeline deployed the BPMN
 * resources and started workflow processing BEFORE any recovered entry is dispatched
 * (see {@link VanillaBpDeploymentRunner#OUTBOX_DISPATCHER_STARTUP_PRIORITY}).
 */
@ApplicationScoped
@Slf4j
public class JdbcPhaseTwoOutbox implements PhaseTwoOutbox, PlatformDefaultStore {

  @Inject
  Instance<DataSource> dataSource;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  /**
   * What a blocked entry is counted into. Unsatisfied where the application uses no
   * Micrometer extension, which is why it is resolved through the producer's helper
   * rather than injected directly.
   */
  @Inject
  Instance<VanillaBpMetrics> vanillaBpMetrics;

  private volatile PhaseTwoOutboxProperties properties;

  private volatile JdbcPhaseTwoOutboxStore store;

  private volatile JdbcPhaseTwoOutboxDispatcher dispatcher;

  private volatile JdbcPhaseTwoPayloadStore payloadStore;

  /**
   * Built by the CDI container. The extension registers this bean whether or not the
   * application has a datasource, so nothing may be read or opened here -
   * {@link #isAvailable()} decides later whether the bean is used at all.
   */
  public JdbcPhaseTwoOutbox() {
  }

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

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    return store().schedule(call);

  }

  @Override
  public boolean scheduleReplacingWhatIsStillWaiting(
      final PhaseTwoCall call) {

    return store().scheduleReplacingWhatIsStillWaiting(call);

  }

  @Override
  public Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return dataSource.isResolvable()
        ? store().adapterIdsOfPendingCalls(workflowModuleId, bpmnProcessId)
        : Set.of();

  }

  @Override
  public OptionalLong pendingCalls() {

    return dataSource.isResolvable()
        ? store().pendingCalls()
        : OptionalLong.empty();

  }

  @Override
  public Optional<Duration> ageOfOldestPendingCall() {

    return dataSource.isResolvable()
        ? store().ageOfOldestPendingCall()
        : Optional.empty();

  }

  /**
   * Creates the tables (unless disabled) and starts the poller. The first poll runs
   * immediately, dispatching committed-but-unprocessed entries of a previously crashed
   * instance.
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
    if (!properties().getJdbc().isEnabled()) {
      log.debug("'vanillabp.outbox.jdbc.enabled' is false - the JDBC-based phase-two outbox stays inactive");
      return;
    }

    dispatcher().prepareSchema();
    dispatcher().start();

  }

  @PreDestroy
  void shutdown() {

    if (dispatcher != null) {
      dispatcher.stop();
      dispatcher = null;
    }

  }

  /**
   * The store, built on first use: a workflow may be started before the startup event
   * was observed, and the entry then has to be written already.
   *
   * @return The store of this outbox
   */
  private JdbcPhaseTwoOutboxStore store() {

    if (store == null) {
      if (!dataSource.isResolvable()) {
        throw new IllegalStateException(
            """
                No datasource available! The JDBC-based phase-two outbox requires a configured \
                default datasource (quarkus-agroal).""");
      }
      synchronized (this) {
        if (store == null) {
          store = new JdbcPhaseTwoOutboxStore(
              connections(), transaction(), JdbcPhaseTwoOutboxStore
                  .tableName(properties()), payloadStore(), () -> dispatcher().triggerPoll());
        }
      }
    }
    return store;

  }

  /**
   * The dispatcher, built on first use like the store, because the store's commit hook
   * names it before the startup event was observed.
   *
   * @return The dispatcher of this outbox
   */
  private JdbcPhaseTwoOutboxDispatcher dispatcher() {

    if (dispatcher == null) {
      synchronized (this) {
        if (dispatcher == null) {
          dispatcher = new JdbcPhaseTwoOutboxDispatcher(
              connections(), properties(), JdbcPhaseTwoOutboxStore
                  .tableName(properties()), payloadStore(), phaseTwoRouter::get, () -> PhaseTwoRouterProducer
                      .vanillaBpMetricsOf(vanillaBpMetrics), JdbcPhaseTwoOutbox.class.getSimpleName());
        }
      }
    }
    return dispatcher;

  }

  /**
   * Where the bytes of a call which carries a payload lie while its entry waits. One
   * store for the write and for the read, because both name the same table.
   *
   * @return The payload store of this outbox
   */
  private synchronized JdbcPhaseTwoPayloadStore payloadStore() {

    if (payloadStore == null) {
      payloadStore = new JdbcPhaseTwoPayloadStore(
          connections(), JdbcPhaseTwoOutboxStore.payloadTableName(properties()), JdbcPhaseTwoOutboxStore
              .entriesNamingTheirPayload(properties()));
    }
    return payloadStore;

  }

  /**
   * An Agroal connection: acquiring it inside a running JTA transaction enlists it
   * there, and closing it hands it back to the pool without ending that transaction.
   *
   * @return How this platform hands out connections
   */
  private JdbcConnectionAccess connections() {

    return new JdbcConnectionAccess() {
      @Override
      public Connection acquire() throws SQLException {

        return dataSource.get().getConnection();

      }
    };

  }

  /**
   * The JTA transaction of the caller, as the core store needs it.
   *
   * @return The transaction handle of this platform
   */
  private PhaseTwoOutboxTransaction transaction() {

    return new PhaseTwoOutboxTransaction() {
      @Override
      public boolean isActive() {

        return txRegistry.getTransactionKey() != null;

      }

      @Override
      public void afterCommit(
          final Runnable action) {

        txRegistry.registerInterposedSynchronization(new Synchronization() {
          @Override
          public void beforeCompletion() {
            // nothing to do
          }

          @Override
          public void afterCompletion(
              final int status) {
            if (status == Status.STATUS_COMMITTED) {
              action.run();
            }
          }
        });

      }
    };

  }

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily so the
   * store can resolve its table name even before the startup event was observed.
   *
   * @return The outbox configuration
   */
  private PhaseTwoOutboxProperties properties() {

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

}
