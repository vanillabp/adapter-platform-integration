package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the election asked of the cache, whichever cache that is. These three numbers
 * are produced by the decorator around the cache rather than by the cache, which is
 * why they are the same for VanillaBP's in-memory default, for a shared cache and for
 * one an application wrote itself.
 * <p>
 * What an implementation knows about itself is asserted where it is produced, in
 * {@code InMemoryWorkflowAdapterCacheStatisticsTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCacheStatisticsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  @Test
  @DisplayName("Hits, misses and the marks of ended workflows are counted for any cache")
  public void theElectionsNumbersHoldForEveryCache() {

    final var statistics = new WorkflowAdapterCacheStatistics();
    final var cache = InstrumentedWorkflowAdapterCache.instrument(new RecordingCache(), statistics);

    cache.put(MODULE, PROCESS, "42", "adapter-a");
    assertTrue(cache.get(MODULE, PROCESS, "42").isPresent());
    cache.putEnded(MODULE, PROCESS, "42", "adapter-a");
    cache.invalidate(MODULE, PROCESS, "42");
    assertTrue(cache.get(MODULE, PROCESS, "42").isEmpty());

    assertEquals(1, statistics.getHits(), "lookups are counted whatever cache is in use");
    assertEquals(1, statistics.getMisses());
    assertEquals(
        1,
        statistics.getEndedMarks(),
        "how often the end of a workflow reached the cache is the first thing to look at "
            + "when the release seems not to work");

  }

  @Test
  @DisplayName("The in-memory default is counted by exactly the same numbers")
  public void theDefaultIsCountedTheSameWay() {

    final var statistics = new WorkflowAdapterCacheStatistics();
    final var cache = InstrumentedWorkflowAdapterCache
        .instrument(new InMemoryWorkflowAdapterCache(), statistics);

    cache.put(MODULE, PROCESS, "42", "adapter-a");
    assertTrue(cache.get(MODULE, PROCESS, "42").isPresent());
    assertTrue(cache.get(MODULE, PROCESS, "unknown").isEmpty());

    assertEquals(1, statistics.getHits());
    assertEquals(1, statistics.getMisses());

  }

  @Test
  @DisplayName("Without statistics the cache works exactly as before")
  public void statisticsAreOptional() {

    final var cache = InstrumentedWorkflowAdapterCache.instrument(
        new InMemoryWorkflowAdapterCache(), null);

    cache.put(MODULE, PROCESS, "42", "adapter-a");
    assertEquals("adapter-a", cache.get(MODULE, PROCESS, "42").orElseThrow());

    // nothing to instrument at all
    org.junit.jupiter.api.Assertions.assertNull(
        InstrumentedWorkflowAdapterCache.instrument(null, new WorkflowAdapterCacheStatistics()));

  }

  /**
   * An application-provided cache: it knows neither size nor evictions.
   */
  private static class RecordingCache implements io.vanillabp.integration.spi.WorkflowAdapterCache {

    private final java.util.Map<String, String> entries = new java.util.concurrent.ConcurrentHashMap<>();

    private static String key(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);

    }

    @Override
    public java.util.Optional<String> get(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      return java.util.Optional.ofNullable(
          entries.get(key(workflowModuleId, bpmnProcessId, workflowAggregateId)));

    }

    @Override
    public void put(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String adapterId) {

      entries.put(key(workflowModuleId, bpmnProcessId, workflowAggregateId), adapterId);

    }

    @Override
    public void invalidate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      entries.remove(key(workflowModuleId, bpmnProcessId, workflowAggregateId));

    }

  }

}
