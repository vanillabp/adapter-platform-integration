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
   * Drop the entry of a workflow - called when a cached hint turned out to be
   * stale (the adapter no longer knows the workflow) or the workflow completed.
   * Invalidating an absent entry is a no-op.
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
