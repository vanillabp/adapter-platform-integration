package io.vanillabp.integration.runtime.workflowtask;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the core-owned {@link WorkflowTaskRegistry} (the adapter-facing
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}):
 * the generated process-service beans register every workflow service class under
 * all BPMN process IDs it declares; adapters validate the wiring during
 * <code>wireBpmn</code> and dispatch task invocations at runtime. Registered by the
 * platform's build step independently of any adapter.
 */
@ApplicationScoped
public class WorkflowTaskRegistryProducer {

  /**
   * Built by the CDI container, which the platform's build step told about this class. The
   * sync model below is built with it, so every bean produced here shares the one instance.
   */
  public WorkflowTaskRegistryProducer() {
  }

  /**
   * The one sync-model instance of the application - handed to adapters as
   * {@link io.vanillabp.integration.adapter.spi.WorkflowAggregateSync} AND to the
   * registry (which validates the model of every registered workflow-aggregate
   * class at startup and answers the shared values of aggregates an adapter does
   * not hold).
   */
  private io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport aggregateSync;

  /**
   * The one sync model, built on the first ask. It reports what it finds about an
   * aggregate class into the collection the configuration carries, and the configuration
   * arrives as a parameter of a producer method rather than beside the field.
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
   * FALLBACK: a delivery runs in the transaction the process service of its workflow aggregate
   * resolved, and this one serves where that resolution answers nothing - see decision 11 in
   * the repository's DECISIONS.md.
   *
   * @param platformTransactionRunner The platform's own runner, the bean of
   *          {@link io.vanillabp.integration.runtime.processservice.TransactionRunnerProducer}
   *          - it also reads the state of the transaction a
   *          <code>&#64;WorkflowTask</code> handler runs in (a rollback-only mark set
   *          by a transaction annotation of the application)
   * @param springTransactionSupport Whether Spring's <code>&#64;Transactional</code> has
   *          an effect on this application, decided at build time
   * @param properties The VanillaBP configuration
   * @param scoping The core's name-clash-avoidance support, which judges the identifiers of
   *          the versions a BPMS still holds
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
   * The handler methods of the extensions - an extension registers its own annotation
   * here and invokes the methods carrying it. It is the registry's own object rather
   * than a second one, because the methods are scanned from the same registration call
   * the <code>&#64;WorkflowTask</code> methods are.
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
   * The core-owned name-clash-avoidance model: resolves the mode per
   * workflow module/workflow and adapter and composes the identifiers a BPMS sees.
   * <p>
   * The adapters' deployment services are looked up LAZILY (they receive this bean, so
   * they cannot be injected here): the mode applying without configuration is the
   * adapter's own, and an unscoped workflow module is reported by the adapter itself.
   * Both bean shapes of the platform contract are accepted - element beans and beans
   * of type <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code>
   * (one instance per configured adapter id), see
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
   * It is the collection the checks of the core report into as well, so an adapter's
   * finding stands in the same block as the platform's. The collection hangs on the
   * configuration, because that is what every check already holds; this bean is the way
   * to it for everything which does not.
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
   * The core-owned sync model: turns a workflow aggregate into the
   * values shared with the BPMS, honoring
   * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's default.
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
