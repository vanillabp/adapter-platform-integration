package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Publishes the election cache's statistics as Micrometer meters. Quarkus applies
 * every {@code MeterBinder} bean to its registry, so producing the binder is all it
 * takes.
 * <p>
 * This class is registered as a bean ONLY if the application uses the Micrometer
 * extension (see {@code WorkflowAdapterCacheMetricsBuildStepProcessor}) - it
 * references Micrometer types and must not be loaded otherwise.
 */
@ApplicationScoped
public class WorkflowAdapterCacheMetricsProducer {

  @Produces
  @Singleton
  public WorkflowAdapterCacheMeters workflowAdapterCacheMeters(
      final WorkflowAdapterCacheStatistics statistics) {

    return new WorkflowAdapterCacheMeters(statistics);

  }

}
