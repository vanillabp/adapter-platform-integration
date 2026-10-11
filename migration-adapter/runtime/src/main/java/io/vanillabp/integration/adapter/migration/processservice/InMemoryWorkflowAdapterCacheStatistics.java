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
 * What only the in-memory election cache can know about itself: how much it holds, and what
 * it had to drop for lack of space. {@link InMemoryWorkflowAdapterCache} owns it. It creates
 * one in its constructor, and it is the only thing which writes to it.
 * <p>
 * These numbers are deliberately NOT part of {@link WorkflowAdapterCacheStatistics}, which
 * counts what holds for every implementation. A size is a property of an implementation, and
 * so is an eviction. The cache which VanillaBP ships for Hazelcast has no size limit at all.
 * So a shared <code>evictions</code> counter would report a zero which can never become
 * anything else. And a shared <code>size</code> gauge would report NaN for the rest of the
 * application's life. That is why there is a prefix of its own, {@value #METER_PREFIX}. And
 * that is why every implementation publishes what it knows under a name which says which
 * implementation it is.
 * <p>
 * <b>Lost hints.</b> An entry evicted before it was ever read is not a defect by itself. A
 * workflow which is started and never touched afterwards leaves exactly such an entry
 * behind. It becomes a defect when that same workflow IS looked up later. Then the limit,
 * and not the workflow, decided the outcome. So the keys of unused evictions are remembered
 * (their hash codes, limited like the cache itself). A later miss on such a key is counted
 * as a LOST HINT: a lookup which would have been a hit with a bigger cache. The warning
 * about eviction pressure is based on that signal. So a healthy application never sees
 * it.
 */
@Slf4j
public class InMemoryWorkflowAdapterCacheStatistics {

  /**
   * The prefix of every meter of this class. It names the implementation. An application
   * which plugs in a cache of its own has no meter which starts like this. That is the
   * intended way to notice.
   */
  public static final String METER_PREFIX = "vanillabp.inmemory.election.cache";

  /**
   * How many hints the cache holds right now. Read it against the configured limit. A size
   * which stays at the limit is where evictions start.
   */
  public static final String METER_SIZE = METER_PREFIX
      + ".size";

  /**
   * How many of the held hints belong to workflows which ended. Those are kept only for a
   * short while. So a big share of them means that the cache mostly holds leftovers.
   */
  public static final String METER_SIZE_ENDED = METER_PREFIX
      + ".size.ended";

  /**
   * Hints dropped because the cache was full. An expired hint is not counted here, because it
   * did its job for a whole time-to-live.
   */
  public static final String METER_EVICTIONS = METER_PREFIX
      + ".evictions";

  /**
   * Hints dropped before anybody read them. On its own this is no defect, see the note about
   * lost hints on this class.
   */
  public static final String METER_EVICTIONS_UNUSED = METER_PREFIX
      + ".evictions.unused";

  /**
   * Lookups which would have been a hit if the cache had been bigger. This number says that
   * the limit is too low, and the warning about eviction pressure counts it.
   */
  public static final String METER_LOST_HINTS = METER_PREFIX
      + ".lost.hints";

  /**
   * How many lost hints have to pile up before the warning about eviction pressure is
   * logged. A single lost hint is a real loss, but a poor reason to shout. It may be the tail
   * of a load peak.
   */
  public static final int LOST_HINTS_WARNING_THRESHOLD = 10;

  /**
   * How often the warning about eviction pressure is logged at most.
   */
  public static final Duration WARNING_INTERVAL = Duration.ofHours(1);

  private final WorkflowAdapterCacheProperties properties;

  /**
   * Where the current number of entries is read from: the cache which owns these
   * statistics, handed over when it creates them. Unlike the numbers of the shared
   * statistics, this one is never missing. That is the whole point of the split.
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
   * The key hashes of entries evicted before they were ever read. They are limited exactly
   * like the cache itself, because an eviction older than a full turn of the cache says
   * nothing about the current load. Hash collisions are possible and acceptable. They can
   * only add to a warning which is about an order of magnitude, never to a decision.
   */
  private final Map<Integer, Boolean> evictedUnused;

  private long lostHintsSinceLastWarning;

  private long observationStartedAtMillis = System.currentTimeMillis();

  private boolean warned;

  /**
   * Created by {@link InMemoryWorkflowAdapterCache} in its own constructor, so the two always
   * belong together. The suppliers read the live cache, instead of the cache pushing its
   * size here on every change.
   *
   * @param properties The cache's configuration. The warning about eviction pressure names
   *          its limit, and the limit also bounds the memory of unused evictions
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

  /**
   * Evictions alone are not a defect, because a cache with a limit evicts. They say that the
   * limit is in play at all.
   *
   * @return How many hints were dropped for lack of space since this application started
   */
  public long getEvictions() {

    return evictions.sum();

  }

  /**
   * The upper limit of what the evictions may have cost. Only a hint which nobody read can
   * still turn into a lost one.
   *
   * @return How many of the dropped hints had never been read. A hint of a workflow which
   *         nobody touches again ends up here, and nothing is wrong
   */
  public long getEvictionsBeforeFirstUse() {

    return evictionsBeforeFirstUse.sum();

  }

  /**
   * The number to alert on, because every one of these lookups paid for the limit with an
   * extra walk over the adapters.
   *
   * @return How many lookups missed although the hint had been there and was dropped for
   *         lack of space. The limit has to answer for this number
   */
  public long getLostHints() {

    return lostHints.sum();

  }

  /**
   * Judges a lookup which found nothing. It is a lost hint where the entry had been dropped
   * for lack of space before it was ever read. The shared statistics count the miss ITSELF,
   * for every implementation. What happens here is the part which only this cache can
   * answer.
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
   * Counts an entry dropped because the size limit was reached. Expiry is NOT an eviction,
   * because the entry did its job for a full time-to-live.
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
   * Logs at most one warning per {@link #WARNING_INTERVAL}, and only once enough hints were
   * lost to make the load the reason, and not a single unlucky workflow. The message names
   * the observation period with the number, so that the rate can be read.
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
