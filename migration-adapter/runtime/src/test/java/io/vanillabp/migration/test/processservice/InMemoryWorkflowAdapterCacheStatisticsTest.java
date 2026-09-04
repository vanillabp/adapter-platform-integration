package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheStatistics;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the in-memory election cache reports about ITSELF: the bounds come from the
 * configuration, an entry dropped for lack of space before it was ever read is told
 * apart from one which did its job, and the warning about eviction pressure appears
 * only where hints were really lost - and then at most once per hour.
 * <p>
 * None of it goes through the decorator around the cache. These numbers belong to the
 * implementation, so the implementation produces them, and a test which needed the
 * decorator to see them would be asserting the wrong thing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InMemoryWorkflowAdapterCacheStatisticsTest {

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

    final var cache = new InMemoryWorkflowAdapterCache(boundedTo(2));
    final var statistics = cache.getStatistics();

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    cache.put(MODULE, PROCESS, "2", "adapter-a");
    assertEquals(2, statistics.getSize());
    assertEquals(0, statistics.getEvictions());

    cache.put(MODULE, PROCESS, "3", "adapter-a");

    assertEquals(2, statistics.getSize(), "the configured bound is honored");
    assertEquals(1, statistics.getEvictions());

  }

  @Test
  @DisplayName("Hints of ended workflows are counted apart from the rest")
  public void endedHintsAreCountedApart() {

    final var cache = new InMemoryWorkflowAdapterCache(boundedTo(10));
    final var statistics = cache.getStatistics();

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    cache.put(MODULE, PROCESS, "2", "adapter-a");
    cache.putEnded(MODULE, PROCESS, "2", "adapter-a");

    assertEquals(2, statistics.getSize());
    assertEquals(
        1,
        statistics.getEndedSize(),
        "an operator has to see how much of the cache is waiting to be released");

  }

  @Test
  @DisplayName("Only an entry evicted before its first read counts towards the pressure")
  public void onlyUnusedEvictionsCountTowardsThePressure() {

    final var cache = new InMemoryWorkflowAdapterCache(boundedTo(2));
    final var statistics = cache.getStatistics();

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

    final var cache = new InMemoryWorkflowAdapterCache(boundedTo(1));
    final var statistics = cache.getStatistics();

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    cache.put(MODULE, PROCESS, "2", "adapter-a");

    assertTrue(cache.get(MODULE, PROCESS, "1").isEmpty());
    assertEquals(1, statistics.getLostHints(), "the bound decided this outcome, not the workflow");

    // a workflow nobody ever cached is a plain miss
    assertTrue(cache.get(MODULE, PROCESS, "never-seen").isEmpty());
    assertEquals(1, statistics.getLostHints());

  }

  @Test
  @DisplayName("Eviction pressure is warned about at most once per hour, naming the property")
  public void evictionPressureIsWarnedAboutOncePerHour() {

    final var warnings = captureWarnings();
    try {

      final var cache = new InMemoryWorkflowAdapterCache(boundedTo(1));

      // fewer lost hints than the threshold: a single unlucky workflow is no reason
      // to shout
      loseHints(cache, 0, InMemoryWorkflowAdapterCacheStatistics.LOST_HINTS_WARNING_THRESHOLD - 1);
      assertTrue(warnings.list.isEmpty(), "a few lost hints must not produce a warning");

      loseHints(cache, 100, InMemoryWorkflowAdapterCacheStatistics.LOST_HINTS_WARNING_THRESHOLD);
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
      assertTrue(
          warning.contains("Hazelcast"),
          "and it has to name the way out for a cluster, which is a shared cache: "
              + warning);

      // the next hour's worth of losses stays silent
      loseHints(cache, 1000, 10 * InMemoryWorkflowAdapterCacheStatistics.LOST_HINTS_WARNING_THRESHOLD);
      assertEquals(1, warnings.list.size(), "at most one warning per hour");

    } finally {
      warnings.detach();
    }

  }

  /**
   * Fills and overflows a cache bounded to one entry, then asks for the entry which
   * was dropped - each round is one lost hint.
   */
  private static void loseHints(
      final InMemoryWorkflowAdapterCache cache,
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
   * Attaches an appender to the statistics' logger - the core's test setup writes no
   * log output at all, so the warning is asserted where it is emitted.
   */
  private static CapturedWarnings captureWarnings() {

    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(InMemoryWorkflowAdapterCacheStatistics.class);
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

}
