package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Micrometer binding of the election cache's statistics (story 58): every number
 * VanillaBP reports arrives in the registry, and the size of a cache which does not
 * report one is NaN instead of a wrong zero.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCacheMetersTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  @Test
  @DisplayName("Hits, misses, evictions and size arrive in the registry")
  public void statisticsArriveInTheRegistry() {

    final var properties = WorkflowAdapterCacheProperties
        .builder()
        .maxEntries(1)
        .timeToLive(Duration.ofHours(1))
        .build();
    final var statistics = new WorkflowAdapterCacheStatistics(properties);
    final var cache = InstrumentedWorkflowAdapterCache.instrument(
        new InMemoryWorkflowAdapterCache(properties, statistics), statistics);

    final var registry = new SimpleMeterRegistry();
    new WorkflowAdapterCacheMeters(statistics).bindTo(registry);

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    cache.put(MODULE, PROCESS, "2", "adapter-a");
    assertTrue(cache.get(MODULE, PROCESS, "2").isPresent());
    assertTrue(cache.get(MODULE, PROCESS, "1").isEmpty());

    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_SIZE).gauge().value());
    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_HITS).functionCounter().count());
    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_MISSES).functionCounter().count());
    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_EVICTIONS).functionCounter().count());
    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_EVICTIONS_UNUSED).functionCounter().count());
    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_LOST_HINTS).functionCounter().count());

  }

  @Test
  @DisplayName("A cache not reporting its size gauges NaN, not zero")
  public void unknownSizeIsNaN() {

    final var statistics = new WorkflowAdapterCacheStatistics();

    final var registry = new SimpleMeterRegistry();
    new WorkflowAdapterCacheMeters(statistics).bindTo(registry);

    assertTrue(
        Double.isNaN(registry.get(WorkflowAdapterCacheStatistics.METER_SIZE).gauge().value()),
        "an application-provided cache does not report a size");

  }

}
