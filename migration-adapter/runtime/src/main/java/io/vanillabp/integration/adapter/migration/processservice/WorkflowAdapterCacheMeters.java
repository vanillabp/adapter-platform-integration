package io.vanillabp.integration.adapter.migration.processservice;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
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
 * The size gauge reports NaN as long as the cache in use does not know its size,
 * which is every implementation but VanillaBP's in-memory default (Micrometer
 * treats NaN as "no measurement" and most backends skip it). The eviction counters
 * stay at zero for the same reason - an application-provided cache manages its own
 * bounds.
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

    Gauge
        .builder(
            WorkflowAdapterCacheStatistics.METER_SIZE,
            statistics,
            currentStatistics -> currentStatistics
                .getSize()
                .stream()
                .mapToDouble(size -> size)
                .findFirst()
                .orElse(Double.NaN))
        .description("Number of BPMS elections currently cached")
        .register(registry);

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
            WorkflowAdapterCacheStatistics.METER_EVICTIONS,
            statistics,
            WorkflowAdapterCacheStatistics::getEvictions)
        .description("Entries dropped because the size bound was reached")
        .register(registry);

    FunctionCounter
        .builder(
            WorkflowAdapterCacheStatistics.METER_EVICTIONS_UNUSED,
            statistics,
            WorkflowAdapterCacheStatistics::getEvictionsBeforeFirstUse)
        .description("Entries dropped for lack of space before they were ever read")
        .register(registry);

    FunctionCounter
        .builder(
            WorkflowAdapterCacheStatistics.METER_LOST_HINTS,
            statistics,
            WorkflowAdapterCacheStatistics::getLostHints)
        .description("Lookups which would have been a hit with a bigger cache")
        .register(registry);

  }

}
