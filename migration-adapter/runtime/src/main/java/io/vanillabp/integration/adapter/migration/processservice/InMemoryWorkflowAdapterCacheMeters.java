package io.vanillabp.integration.adapter.migration.processservice;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.vanillabp.integration.spi.WorkflowAdapterCache;

/**
 * Publishes what the in-memory election cache knows about itself, under the prefix of that
 * implementation ({@value InMemoryWorkflowAdapterCacheStatistics#METER_PREFIX}). Both
 * platforms apply {@link MeterBinder} beans to their registries themselves. So the binding
 * is the same class on both.
 * <p>
 * It takes the cache which the application ended up with, and not the statistics. Whether
 * these meters exist at all depends on which cache that is. With a shared cache there is no
 * size and there are no evictions to report, and then this binder registers nothing. That is
 * the point of the split. A meter which cannot mean anything is not published, instead of
 * publishing NaN forever.
 * <p>
 * A cache which lives somewhere else reports what IT knows the same way, under a prefix
 * which says which implementation it is. The cache which VanillaBP ships for Hazelcast knows
 * the size of its map across the cluster, and whether this node is alone in it.
 * <p>
 * Micrometer is OPTIONAL. This class is loaded only where the platform integration found
 * Micrometer in the classpath. An application without it runs unchanged and simply reports
 * no metrics.
 */
public class InMemoryWorkflowAdapterCacheMeters implements MeterBinder {

  private final WorkflowAdapterCache cacheInUse;

  /**
   * Built by the platform integration where Micrometer is present, once per application.
   * {@link #bindTo(MeterRegistry)} decides which meters this registers, not this
   * constructor. The cache is a bean, and asking it what it is while the beans are still
   * being built would be too early.
   *
   * @param cacheInUse The cache of this application, whichever implementation it is, or
   *          <code>null</code> where the application has none at all. Then its elections
   *          probe every time, and there is nothing to report
   */
  public InMemoryWorkflowAdapterCacheMeters(
      final WorkflowAdapterCache cacheInUse) {

    this.cacheInUse = cacheInUse;

  }

  @Override
  public void bindTo(
      final MeterRegistry registry) {

    if (!(cacheInUse instanceof InMemoryWorkflowAdapterCache inMemoryCache)) {
      return;
    }

    final var statistics = inMemoryCache.getStatistics();

    Gauge
        .builder(
            InMemoryWorkflowAdapterCacheStatistics.METER_SIZE,
            statistics,
            InMemoryWorkflowAdapterCacheStatistics::getSize)
        .description("Number of BPMS elections currently cached")
        .register(registry);

    Gauge
        .builder(
            InMemoryWorkflowAdapterCacheStatistics.METER_SIZE_ENDED,
            statistics,
            InMemoryWorkflowAdapterCacheStatistics::getEndedSize)
        .description("Number of cached elections whose workflow ended")
        .register(registry);

    FunctionCounter
        .builder(
            InMemoryWorkflowAdapterCacheStatistics.METER_EVICTIONS,
            statistics,
            InMemoryWorkflowAdapterCacheStatistics::getEvictions)
        .description("Entries dropped because the size bound was reached")
        .register(registry);

    FunctionCounter
        .builder(
            InMemoryWorkflowAdapterCacheStatistics.METER_EVICTIONS_UNUSED,
            statistics,
            InMemoryWorkflowAdapterCacheStatistics::getEvictionsBeforeFirstUse)
        .description("Entries dropped for lack of space before they were ever read")
        .register(registry);

    FunctionCounter
        .builder(
            InMemoryWorkflowAdapterCacheStatistics.METER_LOST_HINTS,
            statistics,
            InMemoryWorkflowAdapterCacheStatistics::getLostHints)
        .description("Lookups which would have been a hit with a bigger cache")
        .register(registry);

  }

}
