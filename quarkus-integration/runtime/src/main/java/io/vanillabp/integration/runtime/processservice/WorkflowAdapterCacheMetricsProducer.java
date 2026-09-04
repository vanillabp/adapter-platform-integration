package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Publishes the election cache's numbers as Micrometer meters. Quarkus applies every
 * {@code MeterBinder} bean to its registry, so producing the binders is all it takes.
 * <p>
 * This class is registered as a bean ONLY if the application uses the Micrometer
 * extension (see {@code WorkflowAdapterCacheMetricsBuildStepProcessor}) - it
 * references Micrometer types and must not be loaded otherwise.
 */
@ApplicationScoped
public class WorkflowAdapterCacheMetricsProducer {

  /**
   * @param statistics What the election asked of the cache in use
   * @return The binder of the numbers which hold for every implementation
   */
  @Produces
  @Singleton
  public WorkflowAdapterCacheMeters workflowAdapterCacheMeters(
      final WorkflowAdapterCacheStatistics statistics) {

    return new WorkflowAdapterCacheMeters(statistics);

  }

  /**
   * What the in-memory cache knows about itself, published under the prefix of that
   * implementation. The binder takes the cache the application ended up with and
   * registers nothing where that is a shared cache or one of the application's own: a
   * size which cannot be read is a meter which is not published, rather than one
   * reporting NaN for the rest of the application's life.
   *
   * @param cacheInUse The election cache of this application
   * @return The binder of the in-memory cache's own numbers
   */
  @Produces
  @Singleton
  public InMemoryWorkflowAdapterCacheMeters inMemoryWorkflowAdapterCacheMeters(
      final WorkflowAdapterCache cacheInUse) {

    return new InMemoryWorkflowAdapterCacheMeters(cacheInUse);

  }

}
