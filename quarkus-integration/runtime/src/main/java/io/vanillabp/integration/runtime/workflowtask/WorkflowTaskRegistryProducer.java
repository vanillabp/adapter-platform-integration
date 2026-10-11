package io.vanillabp.integration.runtime.workflowtask;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link WorkflowTaskRegistry} which the core owns. Adapters see it as
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}. The
 * generated process-service beans register every workflow service class under all BPMN
 * process IDs it declares. Adapters check the wiring during <code>wireBpmn</code> and hand
 * task invocations to it at runtime. The platform's build step registers this class, with or
 * without any adapter.
 */
@ApplicationScoped
public class WorkflowTaskRegistryProducer {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * The sync model below belongs to this object. So every bean produced here shares the one
   * instance.
   */
  public WorkflowTaskRegistryProducer() {
  }

  /**
   * The one sync model of the application. Adapters get it as
   * {@link io.vanillabp.integration.adapter.spi.WorkflowAggregateSync}. The registry gets it
   * too. At startup, the registry checks the model of every registered workflow aggregate
   * class. And it answers the shared values of aggregates which an adapter does not hold.
   */
  private io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport aggregateSync;

  /**
   * Returns the one sync model and creates it on the first call. It reports what it finds
   * about an aggregate class into the collection of the configuration. The configuration
   * arrives as a parameter of a producer method, not as a field. That is why the model is not
   * created together with this object.
   *
   * @param properties The VanillaBP configuration
   * @return The sync model of this application
   */
  private synchronized io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport aggregateSync(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    if (aggregateSync == null) {
      aggregateSync = new io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport(
          properties.startupFindings());
    }
    return aggregateSync;

  }

  /**
   * Builds the one registry of the application. The platform's runner goes in as the
   * FALLBACK. A delivery runs in the transaction which the process service of its workflow
   * aggregate resolved. The platform's runner serves where that resolution has no answer. See
   * decision 11 in the repository's DECISIONS.md.
   *
   * @param platformTransactionRunner The platform's own runner, the bean of
   *          {@link io.vanillabp.integration.runtime.processservice.TransactionRunnerProducer}.
   *          It also reads the state of the transaction a <code>&#64;WorkflowTask</code>
   *          handler runs in (a rollback-only mark set by a transaction annotation of the
   *          application)
   * @param springTransactionSupport Whether Spring's <code>&#64;Transactional</code> has
   *          an effect on this application, decided at build time
   * @param properties The VanillaBP configuration
   * @param scoping The core's name-clash-avoidance support. It judges the identifiers of the
   *          versions which a BPMS still holds
   * @return The registry
   */
  @Produces
  @Singleton
  @Unremovable
  public WorkflowTaskRegistry workflowTaskRegistry(
      final QuarkusTransactionRunner platformTransactionRunner,
      final SpringTransactionSupport springTransactionSupport,
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    return new WorkflowTaskRegistry(
        platformTransactionRunner, aggregateSync(properties), QuarkusTransactionAnnotations
            .specs(springTransactionSupport.honored()), properties, scoping);

  }


  /**
   * The handler methods of the extensions. An extension registers its own annotation here
   * and invokes the methods which carry it. This is the registry's own object, not a second
   * one. The reason: the methods are found in the same registration call as the
   * <code>&#64;WorkflowTask</code> methods.
   *
   * @param workflowTaskRegistry The registry owning it
   * @return The extension-facing handler service
   */
  @Produces
  @Singleton
  @Unremovable
  public io.vanillabp.integration.extension.spi.handler.ExtensionHandlers extensionHandlers(
      final WorkflowTaskRegistry workflowTaskRegistry) {

    return workflowTaskRegistry.getExtensionHandlers();

  }

  /**
   * The name-clash-avoidance model which the core owns. It resolves the mode per workflow
   * module or workflow and per adapter. And it builds the identifiers which a BPMS sees.
   * <p>
   * The deployment services of the adapters are looked up LAZILY. They get this bean, so they
   * cannot be injected here. Without configuration, the adapter's own mode applies, and the
   * adapter itself reports a workflow module without scoping. Both bean shapes of the platform
   * contract work: element beans and beans of type
   * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code> (one instance per
   * configured adapter id). See
   * {@link io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner}.
   *
   * @param properties The VanillaBP configuration
   * @param deploymentServices The adapters' deployment services as element beans
   * @param deploymentServiceLists The adapters' deployment services as per-adapter-id
   *          lists
   * @return The name-clash-avoidance support
   */
  @jakarta.enterprise.inject.Produces
  @jakarta.inject.Singleton
  public io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport nameClashAvoidanceSupport(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties,
      @jakarta.enterprise.inject.Any final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.spi.AdapterDeploymentService<?, ?>> deploymentServices,
      @jakarta.enterprise.inject.Any final jakarta.enterprise.inject.Instance<java.util.List<io.vanillabp.integration.adapter.spi.AdapterDeploymentService<Object, Object>>> deploymentServiceLists) {

    return new io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService(
        properties, () -> java.util.stream.Stream
            .concat(
                deploymentServices.stream(),
                deploymentServiceLists
                    .stream()
                    .filter(java.util.Objects::nonNull)
                    .flatMap(java.util.List::stream))
            .filter(
                java.util.Objects::nonNull).<io.vanillabp.integration.adapter.spi.AdapterDeploymentService<?, ?>>map(
                    service -> service)
            .toList());

  }

  /**
   * Where an adapter says what it found while the application starts.
   * <p>
   * The checks of the core report into the same collection. So an adapter's finding shows up
   * in the same block as the platform's. The collection belongs to the configuration, because
   * every check already holds the configuration. This bean is the way to the collection for
   * everything which does not.
   *
   * @param properties The VanillaBP configuration, which carries the collection
   * @return Where a startup check of an adapter reports
   */
  @jakarta.enterprise.inject.Produces
  @jakarta.inject.Singleton
  @Unremovable
  public io.vanillabp.integration.spi.startup.StartupReport startupReport(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    return properties.startupFindings();

  }

  /**
   * The sync model which the core owns. It turns a workflow aggregate into the values shared
   * with the BPMS. It follows {@code @SyncWithBPMS}, {@code @NoSyncWithBPMS} and the adapter's
   * default.
   *
   * @param properties The VanillaBP configuration
   * @return The sync support
   */
  @jakarta.enterprise.inject.Produces
  @jakarta.inject.Singleton
  public io.vanillabp.integration.adapter.spi.WorkflowAggregateSync workflowAggregateSync(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    return aggregateSync(properties);

  }

}
