package io.vanillabp.integration.adapter.spi;

import java.util.Map;

/**
 * Turns a workflow aggregate into the values shared with the BPMS, honoring
 * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's own default
 * ({@link AggregateSyncMode}). Implemented ONCE by the core (the model is
 * BPMS-neutral) and handed to every adapter by the platform integration.
 * <p>
 * Every adapter pushes the values as process variables at every sync point (instance
 * creation, task completion, message correlation, user-task completion), an embedded
 * BPMS included: an engine evaluates its models against its own variables, so a
 * model reading anything else would work on one BPMS and fail on the next. Camunda 7
 * read the aggregate live once and does not any more.
 * <p>
 * <b>The workflow aggregate's ID is never part of these values</b> - how a BPMS
 * identifies the workflow is the adapter's concern (Camunda 7: the business key;
 * Camunda 8 / Process-Engine-API: a process variable named after the aggregate's
 * ID property, see {@code AggregatePersistenceAware#getAggregateIdName()}). That
 * variable is technical and is ALWAYS set, no matter what the sync model says: an
 * aggregate annotated {@code @NoSyncWithBPMS} would otherwise be unaddressable. Both
 * halves are held, the first by {@code WorkflowTaskRegistryTest} of the migration
 * adapter, the second by {@code PeaSharedValuesTest} respectively
 * {@code Camunda8SharedValuesTest} of the adapters storing the ID in a variable.
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

  /**
   * Whether the given name is a readable attribute of the workflow-aggregate class -
   * asked about a name a BPMN model reads, so an adapter can tell "the application
   * clearly meant its aggregate" from "this is a variable of the model".
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param propertyName The name read by the model
   * @return Whether the class has such a readable attribute
   */
  default boolean isAggregateProperty(
      final Class<?> workflowAggregateClass,
      final String propertyName) {

    return false;

  }

  /**
   * Whether that attribute is SHARED with the BPMS, which is what decides whether an
   * expression reading it finds a value or always <code>null</code>.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param propertyName The attribute's name
   * @param adapterDefault The adapter's default for aggregates carrying no annotation
   *          of their own
   * @return Whether the attribute is shared
   */
  default boolean isSharedWithBpms(
      final Class<?> workflowAggregateClass,
      final String propertyName,
      final AggregateSyncMode adapterDefault) {

    return true;

  }

}
