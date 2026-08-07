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
   * The one sync-model instance of the application - handed to adapters as
   * {@link io.vanillabp.integration.adapter.spi.WorkflowAggregateSync} AND to the
   * registry (which validates the model of every registered workflow-aggregate
   * class at startup and answers the shared values of aggregates an adapter does
   * not hold).
   */
  private final io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport aggregateSync = new io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport();

  @Produces
  @Singleton
  @Unremovable
  public WorkflowTaskRegistry workflowTaskRegistry() {

    return new WorkflowTaskRegistry(new QuarkusTransactionRunner(), aggregateSync);

  }


  /**
   * The core-owned name-clash-avoidance model (story 35): resolves the mode per
   * workflow module/workflow and adapter and composes the identifiers a BPMS sees.
   *
   * @param properties The VanillaBP configuration
   * @return The name-clash-avoidance support
   */
  @jakarta.enterprise.inject.Produces
  @jakarta.inject.Singleton
  public io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport nameClashAvoidanceSupport(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    return new io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService(properties);

  }

  /**
   * The core-owned sync model (story 28): turns a workflow aggregate into the
   * values shared with the BPMS, honoring
   * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's default.
   *
   * @return The sync support
   */
  @jakarta.enterprise.inject.Produces
  @jakarta.inject.Singleton
  public io.vanillabp.integration.adapter.spi.WorkflowAggregateSync workflowAggregateSync() {

    return aggregateSync;

  }

}
