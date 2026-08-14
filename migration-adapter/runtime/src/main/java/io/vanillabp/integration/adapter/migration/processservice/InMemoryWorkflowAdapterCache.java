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
 * The cache reports its size and its evictions to the application's
 * {@link WorkflowAdapterCacheStatistics}, including whether an evicted entry had
 * ever been read - that is what the eviction-pressure warning is made of.
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

    private boolean used;

    private Entry(
        final String adapterId,
        final long expiresAtMillis) {

      this.adapterId = adapterId;
      this.expiresAtMillis = expiresAtMillis;

    }

  }

  private final int maxEntries;

  private final long timeToLiveMillis;

  private final WorkflowAdapterCacheStatistics statistics;

  private final Map<Key, Entry> entries;

  public InMemoryWorkflowAdapterCache() {

    this(new WorkflowAdapterCacheProperties(), null);

  }

  public InMemoryWorkflowAdapterCache(
      final WorkflowAdapterCacheProperties properties,
      final WorkflowAdapterCacheStatistics statistics) {

    this(properties.getMaxEntries(), properties.getTimeToLive(), statistics);

  }

  /**
   * Visible for tests - production code passes the configured
   * {@link WorkflowAdapterCacheProperties}.
   */
  public InMemoryWorkflowAdapterCache(
      final int maxEntries,
      final Duration timeToLive) {

    this(maxEntries, timeToLive, null);

  }

  public InMemoryWorkflowAdapterCache(
      final int maxEntries,
      final Duration timeToLive,
      final WorkflowAdapterCacheStatistics statistics) {

    this.maxEntries = maxEntries;
    this.timeToLiveMillis = timeToLive.toMillis();
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

  private void reportEviction(
      final Key key,
      final Entry entry) {

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
    final var entry = new Entry(adapterId, System.currentTimeMillis() + timeToLiveMillis);
    synchronized (entries) {
      entries.put(key, entry);
    }

  }

  @Override
  public void invalidate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var key = new Key(workflowModuleId, bpmnProcessId, workflowAggregateId);
    synchronized (entries) {
      entries.remove(key);
    }

  }

}
