package io.vanillabp.integration.adapter.migration.processservice;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes the {@link WorkflowAdapterCacheStatistics} as Micrometer meters. Both
 * platforms apply {@link MeterBinder} beans to their registries by themselves
 * (Spring Boot through the Actuator's metrics auto-configuration, Quarkus through
 * the Micrometer extension), so the binding is the same class on both.
 * <p>
 * Micrometer is OPTIONAL: this class is loaded only where the platform integration
 * found Micrometer in the classpath - an application without it runs unchanged and
 * simply reports no metrics.
 * <p>
 * Every meter here holds for every implementation, because the numbers come from the
 * decorator around the cache rather than from the cache. What an implementation knows
 * about itself is published under a prefix of its own, so that no meter of this class
 * can ever report a NaN or a zero which cannot become anything else (the in-memory
 * default: {@link InMemoryWorkflowAdapterCacheMeters}).
 */
public class WorkflowAdapterCacheMeters implements MeterBinder {

  private final WorkflowAdapterCacheStatistics statistics;

  public WorkflowAdapterCacheMeters(
      final WorkflowAdapterCacheStatistics statistics) {

    this.statistics = statistics;

  }

  @Override
  public void bindTo(
      final MeterRegistry registry) {

    FunctionCounter
        .builder(
            WorkflowAdapterCacheStatistics.METER_HITS,
            statistics,
            WorkflowAdapterCacheStatistics::getHits)
        .description("Elections answered from the cache")
        .register(registry);

    FunctionCounter
        .builder(
            WorkflowAdapterCacheStatistics.METER_MISSES,
            statistics,
            WorkflowAdapterCacheStatistics::getMisses)
        .description("Elections which had to probe the prioritized adapters")
        .register(registry);

    FunctionCounter
        .builder(
            WorkflowAdapterCacheStatistics.METER_ENDED_MARKS,
            statistics,
            WorkflowAdapterCacheStatistics::getEndedMarks)
        .description("Cached elections whose workflow ended and which are kept only briefly")
        .register(registry);

  }

}
