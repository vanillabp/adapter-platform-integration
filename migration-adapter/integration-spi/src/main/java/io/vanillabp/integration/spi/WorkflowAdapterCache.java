package io.vanillabp.integration.spi;

import java.util.Optional;

/**
 * A cache of the answer to &quot;which BPMS adapter holds the workflow of this
 * aggregate&quot;. The BPMS election reads it before it asks the prioritized adapters
 * (see the {@code WorkflowLocator} of the migration adapter). Entries are
 * <b>hints, not truth</b>. If the cache names an adapter which no longer knows the
 * workflow, the election falls back to asking all adapters and repairs the entry. So a
 * wrong or missing entry never leads to a wrong result, only to one more question.
 * <p>
 * VanillaBP provides a default which lives in memory, is limited in size and expires.
 * It lives in one application instance. An application whose instances should share
 * their elections has two ways to get there. One is the implementation VanillaBP ships
 * for Hazelcast, where the nodes of the application form the cluster themselves
 * (<a href="https://github.com/vanillabp/hazelcast-shared-election-cache">hazelcast-shared-election-cache</a>,
 * a dependency and the settings which say where the other nodes are). The other is a
 * bean of its own which implements this interface on whatever cache infrastructure it
 * already runs. The second way is not the lesser one. This interface is the contract,
 * and it was written for an application on Redis or on a table of its own as well.
 * <p>
 * Implementations must be thread-safe. All keys travel in serialized (String)
 * form. The workflow-aggregate ID uses the same serialized form as the
 * {@link PhaseTwoOutbox} (checked at startup to convert back without loss).
 * <p>
 * Why an entry of this cache is a hint which is checked, and not an answer which is trusted, is
 * decision 5 in the repository's DECISIONS.md.
 */
public interface WorkflowAdapterCache {

  /**
   * Looks up the adapter hint for a workflow.
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
   * What a cache entry knows: which adapter held the workflow, and what that BPMS itself
   * calls the workflow.
   *
   * @param adapterId The ID of the adapter which held the workflow at the time the entry
   *          was put
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> if nothing
   *          knew it when the entry was written. It is a HINT like the adapter id. It may
   *          be missing, and it says what was true then, not what is true now
   */
  record Hint(
              String adapterId,
              String workflowId) {
  }

  /**
   * Looks up the whole entry of a workflow. That is {@link #get} plus the BPMS' own id of
   * the workflow.
   * <p>
   * It is one call and not two, because both values are in one entry, and a shared cache
   * costs a round trip per read. The default answers what {@link #get} answers and leaves
   * the workflow id empty. That is what a cache written before this method existed knows.
   * A caller then behaves as it did before the id travelled at all.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @return The entry, or {@link Optional#empty()} if not cached (or expired)
   */
  default Optional<Hint> hintOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return get(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .map(adapterId -> new Hint(adapterId, null));

  }

  /**
   * Stores the adapter elected for a workflow, together with the BPMS' own id of that
   * workflow, if VanillaBP learned one. Everything
   * {@link #put(String, String, String, String)} says holds here as well.
   * <p>
   * What the id is good for: the paths which WAIT for a BPMS with eventual consistency pass
   * it to the adapter. The adapter can then ask its engine by key, instead of searching its
   * read model. Measured on Camunda 8, the engine answers after about 20 ms, while the
   * search needs up to two seconds. An extension may also ask for it
   * (<code>WorkflowElection</code>).
   * <p>
   * The default drops the id and stores only the adapter. That is what every cache
   * written before this method existed does.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The ID of the elected adapter
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> if nobody
   *          named one
   */
  default void put(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final String workflowId) {

    put(workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId);

  }

  /**
   * Stores the adapter elected for a workflow. It is called after every successful
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
   * Stores the adapter of a workflow which ENDED. The hint stays readable. Some operations
   * arrive after the end: a <code>completeTask</code> which lost a race with a timeout, a
   * message correlated by an endpoint which did not learn about the end, an outbox entry
   * dispatched afterwards, a read of the viewer API. They still ask the adapter which held
   * the workflow. It answers "completed", and the operation does nothing except log a
   * warning. Without the hint, it would ask every adapter and fail, if the BPMS has
   * forgotten the instance in the meantime. What changes is how long the hint is worth
   * keeping. It can never become useful again. So an implementation which can tell gives
   * it a lifetime of its own (the in-memory default:
   * <code>vanillabp.workflow-adapter-cache.ended-time-to-live</code>, five minutes,
   * against an hour for a living workflow).
   * <p>
   * The key is workflow module, BPMN process and aggregate ID. It does NOT name the
   * instance, so a second workflow on the same aggregate writes the same entry. So an
   * entry which names ANOTHER adapter is left alone. Only an election of that second
   * workflow can have written it, and the end reported here is the older knowledge.
   * <p>
   * The default marks nothing and stores the hint like any other. That is what every
   * cache written before this method existed does. Such a cache keeps behaving exactly as
   * it did. The price is that it holds the hint of an ended workflow for a full
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
   * Drops the entry of a workflow. It is called when a cached hint turned out to be
   * stale (the adapter no longer knows the workflow). The end of a workflow does NOT
   * drop its entry, it marks it
   * ({@link #putEnded(String, String, String, String)}). Dropping an entry which is not
   * there does nothing.
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
