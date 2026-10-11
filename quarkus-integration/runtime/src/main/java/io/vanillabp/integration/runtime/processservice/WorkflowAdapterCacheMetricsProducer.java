package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Publishes the numbers of the election cache as Micrometer meters. Quarkus applies every
 * {@code MeterBinder} bean to its registry. So producing the binders is all it takes.
 * <p>
 * This class is registered as a bean ONLY if the application uses the Micrometer extension
 * (see {@code WorkflowAdapterCacheMetricsBuildStepProcessor}). It uses Micrometer types and
 * must not be loaded otherwise.
 */
@ApplicationScoped
public class WorkflowAdapterCacheMetricsProducer {

  /**
   * The CDI container creates it, and only in an application which has the Micrometer
   * extension. The build step named in the class comment registers the bean.
   */
  public WorkflowAdapterCacheMetricsProducer() {
  }

  /**
   * Builds the binder of the numbers which every cache has: what the election asked and what
   * it got back. They belong to the application and not to an implementation. So they are
   * published whatever cache an application ended up with.
   *
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
   * What the cache in memory knows about itself, published under the prefix of that
   * implementation. The binder takes the cache which the application ended up with. It
   * registers nothing where that is a shared cache or one of the application's own. If a
   * size cannot be read, its meter is not published. That is better than a meter which
   * reports NaN for the rest of the application's life.
   *
   * @param cacheInUse The election cache of this application
   * @return The binder of the own numbers of the cache in memory
   */
  @Produces
  @Singleton
  public InMemoryWorkflowAdapterCacheMeters inMemoryWorkflowAdapterCacheMeters(
      final WorkflowAdapterCache cacheInUse) {

    return new InMemoryWorkflowAdapterCacheMeters(cacheInUse);

  }

}
