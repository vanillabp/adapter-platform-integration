package io.vanillabp.integration.adapter.migration.processservice;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * What the BPMS election's cache did for this application: hits, misses, evictions
 * and - the number worth acting on - the hints which were dropped for lack of space
 * and looked up again afterwards.
 * <p>
 * One instance per application, shared by the
 * {@link InstrumentedWorkflowAdapterCache} decorators of all process services (they
 * count the lookups) and by the {@link InMemoryWorkflowAdapterCache} (which is the
 * only implementation knowing its size and its evictions). An
 * application-provided cache bean is counted as well, but reports neither size nor
 * evictions - its bounds are the application's business.
 * <p>
 * <b>Lost hints.</b> An entry evicted before it was ever read is not by itself a
 * defect: a workflow which is started and never operated on afterwards leaves
 * exactly such an entry behind. It becomes one when that same workflow IS looked up
 * later, because then the bound - not the workflow - decided the outcome. The keys
 * of unused evictions are therefore remembered (their hash codes, bounded like the
 * cache itself) and a later miss on such a key is counted as a LOST HINT: a lookup
 * which would have been a hit with a bigger cache. That is the signal the
 * eviction-pressure warning is based on, so a healthy application never sees it.
 * <p>
 * Metric names are declared here and registered by whoever binds them to a metrics
 * backend (see {@code WorkflowAdapterCacheMeters} for the Micrometer binding).
 */
@Slf4j
public class WorkflowAdapterCacheStatistics {

  public static final String METER_PREFIX = "vanillabp.workflow.adapter.cache";

  public static final String METER_SIZE = METER_PREFIX
      + ".size";

  public static final String METER_HITS = METER_PREFIX
      + ".hits";

  public static final String METER_MISSES = METER_PREFIX
      + ".misses";

  public static final String METER_EVICTIONS = METER_PREFIX
      + ".evictions";

  public static final String METER_EVICTIONS_UNUSED = METER_PREFIX
      + ".evictions.unused";

  public static final String METER_LOST_HINTS = METER_PREFIX
      + ".lost.hints";

  /**
   * How many lost hints have to pile up before the eviction-pressure warning is
   * logged. A single lost hint is a real loss but a poor reason to shout - it may
   * be the tail of a load peak.
   */
  public static final int LOST_HINTS_WARNING_THRESHOLD = 10;

  /**
   * How often the eviction-pressure warning is logged at most.
   */
  public static final Duration WARNING_INTERVAL = Duration.ofHours(1);

  private final WorkflowAdapterCacheProperties properties;

  private final LongAdder hits = new LongAdder();

  private final LongAdder misses = new LongAdder();

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

  /**
   * Reports the current number of entries, registered by the cache implementation
   * knowing it (the in-memory default) or <code>null</code>.
   */
  private volatile IntSupplier sizeSupplier;

  private long lostHintsSinceLastWarning;

  private long observationStartedAtMillis = System.currentTimeMillis();

  private boolean warned;

  public WorkflowAdapterCacheStatistics() {

    this(new WorkflowAdapterCacheProperties());

  }

  public WorkflowAdapterCacheStatistics(
      final WorkflowAdapterCacheProperties properties) {

    this.properties = properties;
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
   * Registers where the current number of entries is read from - called by a cache
   * implementation which knows it.
   *
   * @param sizeSupplier Reports the current number of entries
   */
  public void registerSize(
      final IntSupplier sizeSupplier) {

    this.sizeSupplier = sizeSupplier;

  }

  /**
   * The current number of entries, or {@link OptionalInt#empty()} if the cache in
   * use does not report it (every implementation but VanillaBP's in-memory
   * default).
   *
   * @return The number of entries held
   */
  public OptionalInt getSize() {

    final var supplier = sizeSupplier;
    return supplier == null
        ? OptionalInt.empty()
        : OptionalInt.of(supplier.getAsInt());

  }

  public long getHits() {

    return hits.sum();

  }

  public long getMisses() {

    return misses.sum();

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
   * Counts a lookup which found a hint.
   */
  public void recordHit() {

    hits.increment();

  }

  /**
   * Counts a lookup which found nothing - and checks whether the missing entry was
   * one dropped for lack of space (a lost hint).
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   */
  public void recordMiss(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    misses.increment();

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
          cost roughly 30 MB of heap). If the application runs as a cluster, a shared cache serves \
          the same purpose - provide a bean implementing io.vanillabp.integration.spi.WorkflowAdapterCache."""
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
