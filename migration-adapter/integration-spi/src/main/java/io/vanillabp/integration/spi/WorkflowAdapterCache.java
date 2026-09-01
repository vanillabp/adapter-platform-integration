package io.vanillabp.integration.spi;

import java.util.Optional;

/**
 * Cache of the association &quot;which BPMS adapter holds the workflow of this
 * aggregate&quot;, consulted by the BPMS election before probing the prioritized
 * adapters (see the migration adapter's {@code WorkflowLocator}). Entries are
 * <b>hints, not truth</b>: on a cache hit whose adapter no longer knows the
 * workflow, the election falls through to the full probing walk and repairs the
 * entry - a wrong or missing entry never produces a wrong result, only an extra
 * probe.
 * <p>
 * VanillaBP provides a bounded, expiring in-memory default. An application may
 * override it by defining its own bean implementing this interface - e.g. backed
 * by the application's own distributed cache infrastructure, so that instances of
 * a cluster share elections. VanillaBP deliberately does NOT ship a distributed
 * implementation (the cache infrastructure is the application's concern).
 * <p>
 * Implementations must be thread-safe. All keys travel in serialized (String)
 * form - the workflow-aggregate ID is the same serialized form used by the
 * {@link PhaseTwoOutbox} (validated to round-trip losslessly at startup).
 * <p>
 * Why an entry of this cache is a hint which is probed rather than an answer which is trusted is
 * decision 5 in the repository's DECISIONS.md.
 */
public interface WorkflowAdapterCache {

  /**
   * Look up the adapter hint for a workflow.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @return The ID of the adapter which held the workflow at the time the entry
   *         was put, or {@link Optional#empty()} if not cached (or expired)
   */
  Optional<String> get(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId);

  /**
   * Store the adapter elected for a workflow. Called after every successful
   * election (an adapter answered ACTIVE).
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The ID of the elected adapter
   */
  void put(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId,
      String adapterId);

  /**
   * Store the adapter of a workflow which ENDED. The hint stays readable - an
   * operation arriving after the end (a <code>completeTask</code> which lost a race
   * with a timeout, a message correlated by an endpoint which did not learn about the
   * end, an outbox entry dispatched afterwards, a read of the viewer API) still asks
   * the adapter which held the workflow, gets "completed" and becomes a warned no-op
   * instead of walking every adapter and failing where the BPMS has forgotten the
   * instance meanwhile. What changes is how long the hint is worth keeping: it can
   * never become useful again, so an implementation which can say so gives it a
   * lifetime of its own (the in-memory default:
   * <code>vanillabp.workflow-adapter-cache.ended-time-to-live</code>, five minutes,
   * against an hour for a living workflow).
   * <p>
   * The key is workflow module, BPMN process and aggregate ID and does NOT name the
   * instance, so a second workflow on the same aggregate writes the same entry. An
   * entry naming ANOTHER adapter is therefore left untouched: only an election of that
   * second workflow can have written it, and the end reported here is the older
   * knowledge of the two.
   * <p>
   * The default marks nothing and stores the hint like any other, which is what every
   * cache written before this method existed does - such a cache keeps behaving exactly
   * as it did, at the price of holding the hint of an ended workflow for a full
   * {@link #put(String, String, String, String)} lifetime.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The ID of the adapter which held the ended workflow
   */
  default void putEnded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId) {

    put(workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId);

  }

  /**
   * Drop the entry of a workflow - called when a cached hint turned out to be
   * stale (the adapter no longer knows the workflow). The end of a workflow does NOT
   * drop its entry, it marks it
   * ({@link #putEnded(String, String, String, String)}). Invalidating an absent entry
   * is a no-op.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   */
  void invalidate(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId);

}
