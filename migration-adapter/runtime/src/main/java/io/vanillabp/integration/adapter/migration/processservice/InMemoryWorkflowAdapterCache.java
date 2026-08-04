package io.vanillabp.integration.adapter.migration.processservice;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.vanillabp.integration.spi.WorkflowAdapterCache;

/**
 * The default {@link WorkflowAdapterCache}: a bounded, expiring in-memory
 * LRU-cache. The bounds are fixed, sensible defaults (deliberately no
 * configuration knob - &quot;optimize late&quot;; entries are hints, so an
 * eviction or expiry only costs an extra probe, never correctness). Cluster
 * setups wanting instances to share elections define their own bean implementing
 * {@link WorkflowAdapterCache} backed by their cache infrastructure instead.
 * <p>
 * One instance is shared by all process services of the application (the key
 * includes workflow module and BPMN process, the size bound applies globally).
 */
public class InMemoryWorkflowAdapterCache implements WorkflowAdapterCache {

  /**
   * Maximum number of entries held - the least recently used entry is evicted
   * beyond that.
   */
  public static final int MAX_ENTRIES = 10_000;

  /**
   * Time after which an entry expires (counted from its last {@link #put}).
   */
  public static final Duration TIME_TO_LIVE = Duration.ofHours(1);

  private record Key(
                     String workflowModuleId,
                     String bpmnProcessId,
                     String workflowAggregateId) {
  }

  private record Entry(
                       String adapterId,
                       long expiresAtMillis) {
  }

  private final int maxEntries;

  private final long timeToLiveMillis;

  private final Map<Key, Entry> entries;

  public InMemoryWorkflowAdapterCache() {

    this(MAX_ENTRIES, TIME_TO_LIVE);

  }

  /**
   * Visible for tests - production code uses the fixed defaults of the no-arg
   * constructor.
   */
  public InMemoryWorkflowAdapterCache(
      final int maxEntries,
      final Duration timeToLive) {

    this.maxEntries = maxEntries;
    this.timeToLiveMillis = timeToLive.toMillis();
    // access-ordered LinkedHashMap = LRU; all access synchronized on it
    this.entries = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(
          final Map.Entry<Key, Entry> eldest) {
        return size() > InMemoryWorkflowAdapterCache.this.maxEntries;
      }
    };

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
      if (entry.expiresAtMillis() < System.currentTimeMillis()) {
        entries.remove(key);
        return Optional.empty();
      }
      return Optional.of(entry.adapterId());
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
