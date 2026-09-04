package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Optional;

import io.vanillabp.integration.spi.WorkflowAdapterCache;

/**
 * Counts what the BPMS election asks of the cache and passes every call on
 * unchanged. EVERY implementation is wrapped, VanillaBP's in-memory default as well
 * as an application-provided bean, so hits and misses are reported whatever cache is
 * in use - a metric which disappears once an application plugs in its own cache
 * would surprise exactly the operator who needs it. Size and evictions are a
 * different matter: only the implementation itself knows them, so it reports them
 * under a name of its own (the in-memory default:
 * {@link InMemoryWorkflowAdapterCacheStatistics}) rather than into the numbers of the
 * election.
 * <p>
 * The decorator is created per process service while the statistics are one per
 * application - the numbers are of the cache, not of a workflow.
 */
public class InstrumentedWorkflowAdapterCache implements WorkflowAdapterCache {

  private final WorkflowAdapterCache delegate;

  private final WorkflowAdapterCacheStatistics statistics;

  /**
   * Wraps a cache for counting, tolerating both parts being absent: without a cache
   * there is nothing to count (the election probes every time), and without
   * statistics the cache is used as it is.
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
  public void put(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId) {

    delegate.put(workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId);

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
