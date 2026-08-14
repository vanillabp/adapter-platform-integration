package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the election cache reports about itself (story 58): the bounds come from the
 * configuration, an entry dropped for lack of space before it was ever read is told
 * apart from one which did its job, and the warning about eviction pressure appears
 * only where hints were really lost - and then at most once per hour.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCacheStatisticsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static WorkflowAdapterCacheProperties boundedTo(
      final int maxEntries) {

    return WorkflowAdapterCacheProperties
        .builder()
        .maxEntries(maxEntries)
        .timeToLive(Duration.ofHours(1))
        .build();

  }

  @Test
  @DisplayName("The bounds of the in-memory cache come from the configuration")
  public void boundsComeFromConfiguration() {

    final var statistics = new WorkflowAdapterCacheStatistics(boundedTo(2));
    final var cache = new InMemoryWorkflowAdapterCache(boundedTo(2), statistics);

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    cache.put(MODULE, PROCESS, "2", "adapter-a");
    assertEquals(2, statistics.getSize().orElseThrow());
    assertEquals(0, statistics.getEvictions());

    cache.put(MODULE, PROCESS, "3", "adapter-a");

    assertEquals(2, statistics.getSize().orElseThrow(), "the configured bound is honored");
    assertEquals(1, statistics.getEvictions());

  }

  @Test
  @DisplayName("Only an entry evicted before its first read counts towards the pressure")
  public void onlyUnusedEvictionsCountTowardsThePressure() {

    final var statistics = new WorkflowAdapterCacheStatistics(boundedTo(2));
    final var cache = InstrumentedWorkflowAdapterCache.instrument(
        new InMemoryWorkflowAdapterCache(boundedTo(2), statistics), statistics);

    cache.put(MODULE, PROCESS, "used", "adapter-a");
    cache.put(MODULE, PROCESS, "unused", "adapter-a");
    // read "used" - it did its job AND becomes the most recently used entry, so the
    // next put evicts "unused"
    assertTrue(cache.get(MODULE, PROCESS, "used").isPresent());
    cache.put(MODULE, PROCESS, "another", "adapter-a");

    assertEquals(1, statistics.getEvictions());
    assertEquals(1, statistics.getEvictionsBeforeFirstUse());
    assertEquals(0, statistics.getLostHints(), "nobody asked for the evicted entry yet");

    // now "used" is the least recently used entry and IS evicted having been read
    cache.put(MODULE, PROCESS, "yet-another", "adapter-a");

    assertEquals(2, statistics.getEvictions());
    assertEquals(1, statistics.getEvictionsBeforeFirstUse(), "a used entry is no pressure");

  }

  @Test
  @DisplayName("A lookup of an entry evicted unused is counted as a lost hint")
  public void lookupOfAnEvictedEntryIsALostHint() {

    final var statistics = new WorkflowAdapterCacheStatistics(boundedTo(1));
    final var cache = InstrumentedWorkflowAdapterCache.instrument(
        new InMemoryWorkflowAdapterCache(boundedTo(1), statistics), statistics);

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    cache.put(MODULE, PROCESS, "2", "adapter-a");

    assertTrue(cache.get(MODULE, PROCESS, "1").isEmpty());
    assertEquals(1, statistics.getMisses());
    assertEquals(1, statistics.getLostHints(), "the bound decided this outcome, not the workflow");

    // a workflow nobody ever cached is a plain miss
    assertTrue(cache.get(MODULE, PROCESS, "never-seen").isEmpty());
    assertEquals(2, statistics.getMisses());
    assertEquals(1, statistics.getLostHints());

    // and a hit stays a hit
    assertTrue(cache.get(MODULE, PROCESS, "2").isPresent());
    assertEquals(1, statistics.getHits());

  }

  @Test
  @DisplayName("Eviction pressure is warned about at most once per hour, naming the property")
  public void evictionPressureIsWarnedAboutOncePerHour() {

    final var warnings = captureWarnings();
    try {

      final var statistics = new WorkflowAdapterCacheStatistics(boundedTo(1));
      final var cache = InstrumentedWorkflowAdapterCache.instrument(
          new InMemoryWorkflowAdapterCache(boundedTo(1), statistics), statistics);

      // fewer lost hints than the threshold: a single unlucky workflow is no reason
      // to shout
      loseHints(cache, 0, WorkflowAdapterCacheStatistics.LOST_HINTS_WARNING_THRESHOLD - 1);
      assertTrue(warnings.list.isEmpty(), "a few lost hints must not produce a warning");

      loseHints(cache, 100, WorkflowAdapterCacheStatistics.LOST_HINTS_WARNING_THRESHOLD);
      assertEquals(1, warnings.list.size(), "expected exactly one warning");
      final var warning = warnings.list.getFirst().getFormattedMessage();
      assertTrue(
          warning.contains(WorkflowAdapterCacheProperties.MAX_ENTRIES_PROPERTY),
          "the warning has to name the property to raise but got: "
              + warning);
      assertTrue(
          warning.contains("too small for this application's load"),
          "the warning has to say what is wrong but got: "
              + warning);

      // the next hour's worth of losses stays silent
      loseHints(cache, 1000, 10 * WorkflowAdapterCacheStatistics.LOST_HINTS_WARNING_THRESHOLD);
      assertEquals(1, warnings.list.size(), "at most one warning per hour");

    } finally {
      warnings.detach();
    }

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

  @Test
  @DisplayName("A cache which does not report its size says so")
  public void sizeIsUnknownForAnApplicationProvidedCache() {

    final var statistics = new WorkflowAdapterCacheStatistics();
    final var cache = InstrumentedWorkflowAdapterCache.instrument(
        new RecordingCache(), statistics);

    cache.put(MODULE, PROCESS, "42", "adapter-a");
    assertTrue(cache.get(MODULE, PROCESS, "42").isPresent());
    cache.invalidate(MODULE, PROCESS, "42");
    assertTrue(cache.get(MODULE, PROCESS, "42").isEmpty());

    assertTrue(statistics.getSize().isEmpty(), "only the in-memory default knows its size");
    assertEquals(1, statistics.getHits(), "lookups are counted whatever cache is in use");
    assertEquals(1, statistics.getMisses());
    assertEquals(0, statistics.getEvictions(), "an application's cache manages its own bounds");

  }

  /**
   * Fills and overflows a cache bounded to one entry, then asks for the entry which
   * was dropped - each round is one lost hint.
   */
  private static void loseHints(
      final io.vanillabp.integration.spi.WorkflowAdapterCache cache,
      final int firstAggregateId,
      final int hints) {

    for (var i = 0; i < hints; ++i) {
      final var lost = String.valueOf(firstAggregateId + i);
      cache.put(MODULE, PROCESS, lost, "adapter-a");
      cache.put(MODULE, PROCESS, "evicts-"
          + lost, "adapter-a");
      cache.get(MODULE, PROCESS, lost);
    }

  }

  /**
   * Attaches an appender to the statistics' logger - the core's test setup writes
   * no log output at all, so the warning is asserted where it is emitted.
   */
  private static CapturedWarnings captureWarnings() {

    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(WorkflowAdapterCacheStatistics.class);
    final var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return new CapturedWarnings(appender.list, () -> logger.detachAppender(appender));

  }

  private record CapturedWarnings(
                                  java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> list,
                                  Runnable detachAppender) {

    private void detach() {

      detachAppender.run();

    }

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
