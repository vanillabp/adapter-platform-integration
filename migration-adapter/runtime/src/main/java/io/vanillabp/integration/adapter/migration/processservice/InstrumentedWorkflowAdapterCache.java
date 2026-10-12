package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Optional;

import io.vanillabp.integration.spi.WorkflowAdapterCache;

/**
 * Counts what the BPMS election asks of the cache, and passes every call on unchanged.
 * EVERY implementation is wrapped, VanillaBP's in-memory default as well as a bean which the
 * application provides. So hits and misses are reported whatever cache is in use. A metric
 * which disappears once an application plugs in its own cache would surprise exactly the
 * operator who needs it. Size and evictions are a different matter. Only the implementation
 * itself knows them. So it reports them under a name of its own (for the in-memory default:
 * {@link InMemoryWorkflowAdapterCacheStatistics}), and not in the numbers of the election.
 * <p>
 * The decorator is created per process service, while there is one statistics object per
 * application. The numbers are about the cache, not about a workflow.
 */
public class InstrumentedWorkflowAdapterCache implements WorkflowAdapterCache {

  private final WorkflowAdapterCache delegate;

  private final WorkflowAdapterCacheStatistics statistics;

  /**
   * Wraps a cache for counting. Both parts may be missing. Without a cache there is nothing to
   * count (the election probes every time). Without statistics the cache is used as it
   * is.
   *
   * @param cache The cache in use or <code>null</code>
   * @param statistics The application's statistics or <code>null</code>
   * @return The cache to hand to the election
   */
  public static WorkflowAdapterCache instrument(
      final WorkflowAdapterCache cache,
      final WorkflowAdapterCacheStatistics statistics) {

    if ((cache == null) || (statistics == null)) {
      return cache;
    }
    return new InstrumentedWorkflowAdapterCache(cache, statistics);

  }

  /**
   * Wraps a cache which is really there. Callers use
   * {@link #instrument(WorkflowAdapterCache, WorkflowAdapterCacheStatistics)} instead, which
   * also handles the two cases where there is nothing to wrap. Neither argument may be
   * <code>null</code> here. A <code>null</code> would fail at the first lookup, and not
   * while the application boots.
   *
   * @param delegate The cache every call is passed on to
   * @param statistics The application's counters, which all process services share
   */
  public InstrumentedWorkflowAdapterCache(
      final WorkflowAdapterCache delegate,
      final WorkflowAdapterCacheStatistics statistics) {

    this.delegate = delegate;
    this.statistics = statistics;

  }

  @Override
  public Optional<String> get(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var adapterId = delegate.get(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (adapterId.isPresent()) {
      statistics.recordHit();
    } else {
      statistics.recordMiss();
    }
    return adapterId;

  }

  @Override
  public Optional<Hint> hintOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var hint = delegate.hintOf(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (hint.isPresent()) {
      statistics.recordHit();
    } else {
      statistics.recordMiss();
    }
    return hint;

  }

  @Override
  public void put(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId) {

    delegate.put(workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId);

  }

  @Override
  public void put(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final String workflowId) {

    delegate.put(workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, workflowId);

  }

  @Override
  public void putEnded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId) {

    statistics.recordEndedMark();
    delegate.putEnded(workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId);

  }

  @Override
  public void invalidate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    delegate.invalidate(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

}
