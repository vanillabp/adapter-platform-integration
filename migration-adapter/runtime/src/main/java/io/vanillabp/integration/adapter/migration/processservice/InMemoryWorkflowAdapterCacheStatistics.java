package io.vanillabp.integration.adapter.migration.processservice;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * What only the in-memory election cache can know about itself: how much it holds and
 * what it had to drop for lack of space. Owned by
 * {@link InMemoryWorkflowAdapterCache}, which creates one in its constructor and is
 * the only thing writing to it.
 * <p>
 * These numbers are deliberately NOT part of
 * {@link WorkflowAdapterCacheStatistics}, which counts what holds for every
 * implementation. A size is a property of an implementation, and so is an eviction:
 * the cache VanillaBP ships for Hazelcast has no size bound at all, so a shared
 * <code>evictions</code> counter would report a zero which can never become anything
 * else, and a shared <code>size</code> gauge would report NaN for the rest of the
 * application's life. Hence a prefix of its own,
 * {@value #METER_PREFIX}, and hence every implementation publishes what it knows
 * under a name which says which implementation that is.
 * <p>
 * <b>Lost hints.</b> An entry evicted before it was ever read is not by itself a
 * defect: a workflow which is started and never operated on afterwards leaves exactly
 * such an entry behind. It becomes one when that same workflow IS looked up later,
 * because then the bound - not the workflow - decided the outcome. The keys of unused
 * evictions are therefore remembered (their hash codes, bounded like the cache
 * itself) and a later miss on such a key is counted as a LOST HINT: a lookup which
 * would have been a hit with a bigger cache. That is the signal the eviction-pressure
 * warning is based on, so a healthy application never sees it.
 */
@Slf4j
public class InMemoryWorkflowAdapterCacheStatistics {

  public static final String METER_PREFIX = "vanillabp.inmemory.election.cache";

  public static final String METER_SIZE = METER_PREFIX
      + ".size";

  public static final String METER_SIZE_ENDED = METER_PREFIX
      + ".size.ended";

  public static final String METER_EVICTIONS = METER_PREFIX
      + ".evictions";

  public static final String METER_EVICTIONS_UNUSED = METER_PREFIX
      + ".evictions.unused";

  public static final String METER_LOST_HINTS = METER_PREFIX
      + ".lost.hints";

  /**
   * How many lost hints have to pile up before the eviction-pressure warning is
   * logged. A single lost hint is a real loss but a poor reason to shout - it may be
   * the tail of a load peak.
   */
  public static final int LOST_HINTS_WARNING_THRESHOLD = 10;

  /**
   * How often the eviction-pressure warning is logged at most.
   */
  public static final Duration WARNING_INTERVAL = Duration.ofHours(1);

  private final WorkflowAdapterCacheProperties properties;

  /**
   * Where the current number of entries is read from - the cache which owns these
   * statistics, handed over when it creates them. Unlike the numbers of the shared
   * statistics this one is never absent, which is the whole point of the split.
   */
  private final IntSupplier sizeSupplier;

  /**
   * Where the number of entries of ENDED workflows is read from.
   */
  private final IntSupplier endedSizeSupplier;

  private final LongAdder evictions = new LongAdder();

  private final LongAdder evictionsBeforeFirstUse = new LongAdder();

  private final LongAdder lostHints = new LongAdder();

  /**
   * The key hashes of entries evicted before they were ever read, bounded exactly
   * like the cache itself: an eviction older than a full turn of the cache says
   * nothing about the current load. Hash collisions are possible and acceptable -
   * they can only add to a warning which is about an order of magnitude, never to a
   * decision.
   */
  private final Map<Integer, Boolean> evictedUnused;

  private long lostHintsSinceLastWarning;

  private long observationStartedAtMillis = System.currentTimeMillis();

  private boolean warned;

  /**
   * @param properties The cache's configuration - its bound is what the
   *          eviction-pressure warning names, and it bounds the memory of unused
   *          evictions as well
   * @param sizeSupplier Reports the current number of entries
   * @param endedSizeSupplier Reports how many of them are hints of ended workflows
   */
  public InMemoryWorkflowAdapterCacheStatistics(
      final WorkflowAdapterCacheProperties properties,
      final IntSupplier sizeSupplier,
      final IntSupplier endedSizeSupplier) {

    this.properties = properties;
    this.sizeSupplier = sizeSupplier;
    this.endedSizeSupplier = endedSizeSupplier;
    final var ghostEntries = properties.getMaxEntries();
    this.evictedUnused = new LinkedHashMap<>(16, 0.75f, false) {
      @Override
      protected boolean removeEldestEntry(
          final Map.Entry<Integer, Boolean> eldest) {
        return size() > ghostEntries;
      }
    };

  }

  /**
   * The current number of entries.
   *
   * @return The number of entries held
   */
  public int getSize() {

    return sizeSupplier.getAsInt();

  }

  /**
   * How many of the entries held are hints of workflows which ended.
   *
   * @return The number of entries marked as ended
   */
  public int getEndedSize() {

    return endedSizeSupplier.getAsInt();

  }

  public long getEvictions() {

    return evictions.sum();

  }

  public long getEvictionsBeforeFirstUse() {

    return evictionsBeforeFirstUse.sum();

  }

  public long getLostHints() {

    return lostHints.sum();

  }

  /**
   * Judges a lookup which found nothing: it is a lost hint where the entry had been
   * dropped for lack of space before it was ever read. The miss ITSELF is counted by
   * the shared statistics, which counts it for every implementation - what happens
   * here is the part only this cache can answer.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   */
  public void recordLookupMiss(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var key = keyOf(workflowModuleId, bpmnProcessId, workflowAggregateId);
    final boolean lost;
    synchronized (evictedUnused) {
      lost = evictedUnused.remove(key) != null;
    }
    if (lost) {
      lostHints.increment();
      warnAboutEvictionPressure();
    }

  }

  /**
   * Counts an entry dropped because the size bound was reached (expiry is NOT an
   * eviction: the entry did its job for a full time-to-live).
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param used Whether the entry was read at least once before it was dropped
   */
  public void recordEviction(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final boolean used) {

    evictions.increment();
    if (used) {
      return;
    }

    evictionsBeforeFirstUse.increment();
    final var key = keyOf(workflowModuleId, bpmnProcessId, workflowAggregateId);
    synchronized (evictedUnused) {
      evictedUnused.put(key, Boolean.TRUE);
    }

  }

  private static Integer keyOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return Objects.hash(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

  /**
   * Logs at most one warning per {@link #WARNING_INTERVAL}, and only once enough
   * hints were lost to make the load - not a single unlucky workflow - the reason.
   * The observation period is reported with the number, so the rate is readable.
   */
  private void warnAboutEvictionPressure() {

    final String message;
    synchronized (this) {
      ++lostHintsSinceLastWarning;
      if (lostHintsSinceLastWarning < LOST_HINTS_WARNING_THRESHOLD) {
        return;
      }
      final var now = System.currentTimeMillis();
      final var observedForMillis = now - observationStartedAtMillis;
      if (warned && (observedForMillis < WARNING_INTERVAL.toMillis())) {
        return;
      }
      message = """
          The election cache is too small for this application's load: %d cached BPMS elections were \
          dropped for lack of space within the last %d minutes and looked up again afterwards. Each of \
          them costs an extra probe of all prioritized adapters and, on a BPMS answering from an \
          eventually consistent read model, the waiting period a workflow needs right after its start.
          Raise the property '%s' (currently %d entries; about 300 bytes per entry, so 100.000 entries \
          cost roughly 30 MB of heap). If the application runs as a cluster, a shared cache serves the \
          same purpose and has no bound to raise - VanillaBP ships one for Hazelcast, and any other \
          cache infrastructure is a bean implementing io.vanillabp.integration.spi.WorkflowAdapterCache."""
          .formatted(
              lostHintsSinceLastWarning,
              Math.max(1, observedForMillis / 60_000),
              WorkflowAdapterCacheProperties.MAX_ENTRIES_PROPERTY,
              properties.getMaxEntries());
      lostHintsSinceLastWarning = 0;
      observationStartedAtMillis = now;
      warned = true;
    }
    log.warn(message);

  }

}
