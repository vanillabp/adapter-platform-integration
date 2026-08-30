package io.vanillabp.integration.adapter.migration.processservice;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.spi.WorkflowAdapterCache;

/**
 * The default {@link WorkflowAdapterCache}: a bounded, expiring in-memory
 * LRU-cache. Both bounds are configurable
 * (<code>vanillabp.workflow-adapter-cache.max-entries</code> and
 * <code>.time-to-live</code>, see {@link WorkflowAdapterCacheProperties}) and both
 * are HARD - entries are hints, so an eviction or expiry only costs an extra probe,
 * never correctness, which is why the bound is a number and not a soft reference
 * (the reasoning is written down in <code>migration-adapter/README.md</code>).
 * Cluster setups wanting instances to share elections define their own bean
 * implementing {@link WorkflowAdapterCache} backed by their cache infrastructure
 * instead.
 * <p>
 * One instance is shared by all process services of the application (the key
 * includes workflow module and BPMN process, the size bound applies globally).
 * <p>
 * The hint of a workflow which ENDED is kept as well, and much shorter
 * (<code>.ended-time-to-live</code>, five minutes): it still answers the operation
 * which arrives after the end - which is what keeps such an operation a warned no-op -
 * but it can never become useful again, so it leaves early instead of occupying a place
 * for an hour.
 * <p>
 * The cache reports its size and its evictions to the application's
 * {@link WorkflowAdapterCacheStatistics}, including whether an evicted entry had
 * ever been read - that is what the eviction-pressure warning is made of.
 * <p>
 * Why a stale entry repairs itself instead of being prevented is decision 5 in the repository's
 * DECISIONS.md.
 */
public class InMemoryWorkflowAdapterCache implements WorkflowAdapterCache {

  private record Key(
                     String workflowModuleId,
                     String bpmnProcessId,
                     String workflowAggregateId) {
  }

  /**
   * Not a record: whether the entry was ever read decides whether its eviction is
   * counted as pressure.
   */
  private static final class Entry {

    private final String adapterId;

    private final long expiresAtMillis;

    /**
     * Whether this is the hint of a workflow which ENDED - kept for a much shorter
     * time, and counted separately so an operator can see the release working.
     */
    private final boolean ended;

    private boolean used;

    private Entry(
        final String adapterId,
        final long expiresAtMillis,
        final boolean ended) {

      this.adapterId = adapterId;
      this.expiresAtMillis = expiresAtMillis;
      this.ended = ended;

    }

  }

  private final int maxEntries;

  private final long timeToLiveMillis;

  private final long endedTimeToLiveMillis;

  private final WorkflowAdapterCacheStatistics statistics;

  private final Map<Key, Entry> entries;

  /**
   * How many of the entries held are marks of ended workflows, counted along instead of
   * being recounted: a gauge is read on every scrape and must not walk the map the
   * election needs (see decision 18 in the repository's DECISIONS.md). Guarded by the
   * monitor of {@link #entries}.
   */
  private int endedEntries;

  public InMemoryWorkflowAdapterCache() {

    this(new WorkflowAdapterCacheProperties(), null);

  }

  public InMemoryWorkflowAdapterCache(
      final WorkflowAdapterCacheProperties properties,
      final WorkflowAdapterCacheStatistics statistics) {

    this(
        properties.getMaxEntries(), properties.getTimeToLive(), properties.getEndedTimeToLive(), statistics);

  }

  /**
   * Visible for tests - production code passes the configured
   * {@link WorkflowAdapterCacheProperties}.
   */
  public InMemoryWorkflowAdapterCache(
      final int maxEntries,
      final Duration timeToLive) {

    this(maxEntries, timeToLive, WorkflowAdapterCacheProperties.DEFAULT_ENDED_TIME_TO_LIVE, null);

  }

  /**
   * Visible for tests - production code passes the configured
   * {@link WorkflowAdapterCacheProperties}.
   */
  public InMemoryWorkflowAdapterCache(
      final int maxEntries,
      final Duration timeToLive,
      final Duration endedTimeToLive) {

    this(maxEntries, timeToLive, endedTimeToLive, null);

  }

  public InMemoryWorkflowAdapterCache(
      final int maxEntries,
      final Duration timeToLive,
      final Duration endedTimeToLive,
      final WorkflowAdapterCacheStatistics statistics) {

    this.maxEntries = maxEntries;
    this.timeToLiveMillis = timeToLive.toMillis();
    this.endedTimeToLiveMillis = endedTimeToLive.toMillis();
    this.statistics = statistics;
    // access-ordered LinkedHashMap = LRU; all access synchronized on it
    this.entries = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(
          final Map.Entry<Key, Entry> eldest) {
        if (size() <= InMemoryWorkflowAdapterCache.this.maxEntries) {
          return false;
        }
        InMemoryWorkflowAdapterCache.this.reportEviction(eldest.getKey(), eldest.getValue());
        return true;
      }
    };
    if (statistics != null) {
      statistics.registerSize(this::size);
      statistics.registerEndedSize(this::endedSize);
    }

  }

  /**
   * The number of entries currently held - reported as a metric.
   *
   * @return The number of entries
   */
  public int size() {

    synchronized (entries) {
      return entries.size();
    }

  }

  /**
   * How many of the entries held are hints of workflows which ended - reported as a
   * metric of its own, because that number is what tells an operator whether the
   * release is working: it rises while workflows end and falls again as the shorter
   * lifetime takes those entries away.
   *
   * @return The number of entries marked as ended
   */
  public int endedSize() {

    synchronized (entries) {
      return endedEntries;
    }

  }

  private void reportEviction(
      final Key key,
      final Entry entry) {

    if (entry.ended) {
      --endedEntries;
    }
    if (statistics == null) {
      return;
    }
    statistics.recordEviction(
        key.workflowModuleId(),
        key.bpmnProcessId(),
        key.workflowAggregateId(),
        entry.used);

  }

  @Override
  public Optional<String> get(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var key = new Key(workflowModuleId, bpmnProcessId, workflowAggregateId);
    synchronized (entries) {
      final var entry = entries.get(key);
      if (entry == null) {
        return Optional.empty();
      }
      if (entry.expiresAtMillis < System.currentTimeMillis()) {
        // expiry is not eviction pressure: the entry lived its full time-to-live
        entries.remove(key);
        if (entry.ended) {
          --endedEntries;
        }
        return Optional.empty();
      }
      entry.used = true;
      return Optional.of(entry.adapterId);
    }

  }

  @Override
  public void put(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId) {

    final var key = new Key(workflowModuleId, bpmnProcessId, workflowAggregateId);
    final var entry = new Entry(adapterId, System.currentTimeMillis() + timeToLiveMillis, false);
    synchronized (entries) {
      final var replaced = entries.put(key, entry);
      if ((replaced != null) && replaced.ended) {
        --endedEntries;
      }
    }

  }

  @Override
  public void putEnded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId) {

    final var key = new Key(workflowModuleId, bpmnProcessId, workflowAggregateId);
    final var entry = new Entry(adapterId, System.currentTimeMillis() + endedTimeToLiveMillis, true);
    synchronized (entries) {
      final var current = entries.get(key);
      if ((current != null) && !current.adapterId.equals(adapterId)) {
        // the key names the aggregate and not the instance, so an entry naming another
        // adapter belongs to a second workflow on the same aggregate, elected after the
        // one which just ended - the newer knowledge stays
        return;
      }
      final var replaced = entries.put(key, entry);
      if ((replaced == null) || !replaced.ended) {
        ++endedEntries;
      }
    }

  }

  @Override
  public void invalidate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var key = new Key(workflowModuleId, bpmnProcessId, workflowAggregateId);
    synchronized (entries) {
      final var removed = entries.remove(key);
      if ((removed != null) && removed.ended) {
        --endedEntries;
      }
    }

  }

}
