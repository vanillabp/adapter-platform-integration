package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheStatistics;
import io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Micrometer binding of both sets of numbers: what the election asked of any
 * cache, and what the in-memory default knows about itself. The second set is
 * published under the prefix of that implementation, and a cache which is not it
 * publishes no such meter at all - which is the difference between a number which is
 * missing and one which is wrong.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCacheMetersTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  @Test
  @DisplayName("What the election asked arrives in the registry")
  public void theElectionsNumbersArriveInTheRegistry() {

    final var statistics = new WorkflowAdapterCacheStatistics();
    final var cache = InstrumentedWorkflowAdapterCache
        .instrument(new InMemoryWorkflowAdapterCache(), statistics);

    final var registry = new SimpleMeterRegistry();
    new WorkflowAdapterCacheMeters(statistics).bindTo(registry);

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    assertTrue(cache.get(MODULE, PROCESS, "1").isPresent());
    assertTrue(cache.get(MODULE, PROCESS, "2").isEmpty());
    cache.putEnded(MODULE, PROCESS, "1", "adapter-a");

    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_HITS).functionCounter().count());
    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_MISSES).functionCounter().count());
    assertEquals(
        1.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_ENDED_MARKS).functionCounter().count());

  }

  @Test
  @DisplayName("Size, evictions and lost hints arrive under the in-memory cache's own prefix")
  public void theDefaultsOwnNumbersArriveUnderItsOwnPrefix() {

    final var properties = WorkflowAdapterCacheProperties
        .builder()
        .maxEntries(1)
        .timeToLive(Duration.ofHours(1))
        .build();
    final var inMemoryCache = new InMemoryWorkflowAdapterCache(properties);

    final var registry = new SimpleMeterRegistry();
    new InMemoryWorkflowAdapterCacheMeters(inMemoryCache).bindTo(registry);

    inMemoryCache.put(MODULE, PROCESS, "1", "adapter-a");
    inMemoryCache.put(MODULE, PROCESS, "2", "adapter-a");
    assertTrue(inMemoryCache.get(MODULE, PROCESS, "1").isEmpty());

    assertEquals(
        1.0,
        registry.get(InMemoryWorkflowAdapterCacheStatistics.METER_SIZE).gauge().value());
    assertEquals(
        1.0,
        registry.get(InMemoryWorkflowAdapterCacheStatistics.METER_EVICTIONS).functionCounter().count());
    assertEquals(
        1.0,
        registry
            .get(InMemoryWorkflowAdapterCacheStatistics.METER_EVICTIONS_UNUSED)
            .functionCounter()
            .count());
    assertEquals(
        1.0,
        registry.get(InMemoryWorkflowAdapterCacheStatistics.METER_LOST_HINTS).functionCounter().count());

    // the prefix is a promise to a dashboard, so it is asserted as text
    assertEquals("vanillabp.inmemory.election.cache", InMemoryWorkflowAdapterCacheStatistics.METER_PREFIX);
    assertEquals("vanillabp.workflow.adapter.cache", WorkflowAdapterCacheStatistics.METER_PREFIX);

  }

  @Test
  @DisplayName("How much of the in-memory cache waits to be released is a gauge of its own")
  public void endedHintsHaveAGaugeOfTheirOwn() {

    final var inMemoryCache = new InMemoryWorkflowAdapterCache(
        WorkflowAdapterCacheProperties
            .builder()
            .maxEntries(10)
            .timeToLive(Duration.ofHours(1))
            .endedTimeToLive(Duration.ofMinutes(5))
            .build());

    final var registry = new SimpleMeterRegistry();
    new InMemoryWorkflowAdapterCacheMeters(inMemoryCache).bindTo(registry);

    inMemoryCache.put(MODULE, PROCESS, "1", "adapter-a");
    inMemoryCache.put(MODULE, PROCESS, "2", "adapter-a");
    inMemoryCache.putEnded(MODULE, PROCESS, "2", "adapter-a");

    assertEquals(
        2.0,
        registry.get(InMemoryWorkflowAdapterCacheStatistics.METER_SIZE).gauge().value());
    assertEquals(
        1.0,
        registry.get(InMemoryWorkflowAdapterCacheStatistics.METER_SIZE_ENDED).gauge().value(),
        "an operator has to see how much of the cache is waiting to be released");

  }

  @Test
  @DisplayName("A cache which is not the in-memory one publishes no size and no evictions at all")
  public void aForeignCachePublishesNothingItCannotKnow() {

    final var statistics = new WorkflowAdapterCacheStatistics();
    final var registry = new SimpleMeterRegistry();

    new WorkflowAdapterCacheMeters(statistics).bindTo(registry);
    new InMemoryWorkflowAdapterCacheMeters(new ACacheOfTheApplicationsOwn()).bindTo(registry);

    // not NaN and not zero: absent. A meter which cannot mean anything is worse than a
    // meter which is missing, because a dashboard shows it as a cache which is broken
    for (final var neverPublished : new String[]{
        InMemoryWorkflowAdapterCacheStatistics.METER_SIZE, InMemoryWorkflowAdapterCacheStatistics.METER_SIZE_ENDED, InMemoryWorkflowAdapterCacheStatistics.METER_EVICTIONS, InMemoryWorkflowAdapterCacheStatistics.METER_EVICTIONS_UNUSED, InMemoryWorkflowAdapterCacheStatistics.METER_LOST_HINTS
    }) {
      assertThrows(
          MeterNotFoundException.class,
          () -> registry.get(neverPublished).meter(),
          "no meter of the in-memory cache may exist while another cache is in use: "
              + neverPublished);
    }

    // what the election asks is published for it like for any other cache
    assertEquals(
        0.0,
        registry.get(WorkflowAdapterCacheStatistics.METER_HITS).functionCounter().count());

  }

  /**
   * A cache on infrastructure of the application's own: it knows neither a size nor an
   * eviction, and nothing about this test cares what it does hold.
   */
  private static class ACacheOfTheApplicationsOwn implements WorkflowAdapterCache {

    @Override
    public Optional<String> get(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      return Optional.empty();

    }

    @Override
    public void put(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String adapterId) {

    }

    @Override
    public void invalidate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

    }

  }

}
