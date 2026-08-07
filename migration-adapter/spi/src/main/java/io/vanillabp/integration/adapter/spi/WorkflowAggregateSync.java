package io.vanillabp.integration.adapter.spi;

import java.util.Map;

/**
 * Turns a workflow aggregate into the values shared with the BPMS, honoring
 * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's own default
 * ({@link AggregateSyncMode}). Implemented ONCE by the core (the model is
 * BPMS-neutral) and handed to every adapter by the platform integration.
 * <p>
 * What an adapter does with the values is its own decision: a remote BPMS pushes
 * them as process variables at every sync point (instance creation, task
 * completion, message correlation, user-task completion), an embedded BPMS reads
 * the aggregate live and writes them as context information only.
 * <p>
 * <b>The workflow aggregate's ID is never part of these values</b> - how a BPMS
 * identifies the workflow is the adapter's concern (Camunda 7: the business key;
 * Camunda 8 / Process-Engine-API: a process variable named after the aggregate's
 * ID property, see {@code AggregatePersistenceAware#getAggregateIdName()}). That
 * variable is technical and is ALWAYS set, no matter what the sync model says.
 */
public interface WorkflowAggregateSync {

  /**
   * The values of the given workflow aggregate shared with the BPMS.
   *
   * @param workflowAggregate The workflow aggregate (may be <code>null</code>)
   * @param adapterDefault The adapter's default for aggregates carrying no
   *          annotation of their own
   * @return The values by attribute name - possibly empty, never
   *         <code>null</code>; nested objects are maps, collections are lists
   */
  Map<String, Object> syncedValues(
      Object workflowAggregate,
      AggregateSyncMode adapterDefault);

  /**
   * Validates the sync model of one workflow-aggregate class AND of every type
   * reachable from its attributes: as long as an aggregate carries no annotation at
   * all the adapter decides, but as soon as the application annotates something the
   * intent has to be unambiguous - attributes annotated BOTH ways without the class
   * stating its own mode cannot be interpreted.
   * <p>
   * Called by the PLATFORM INTEGRATION at startup, once per registered
   * workflow-aggregate class (not by adapters): a defect must abort the boot, not
   * surface at the first sync point. Types reachable only at runtime (e.g. a
   * subclass assigned to a supertype attribute, or nested deeper than the model
   * follows) still fail with the same message when they are first shared.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @throws IllegalStateException Naming every ambiguous class, its conflicting
   *           attributes and the fix
   */
  void validateSyncModel(
      Class<?> workflowAggregateClass);

}
