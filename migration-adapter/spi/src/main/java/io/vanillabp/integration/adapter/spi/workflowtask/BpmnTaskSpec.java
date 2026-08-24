package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * Describes one task of an executable BPMN process which is to be wired to a
 * <code>&#64;WorkflowTask</code> method, supplied by the BPMS adapter to
 * {@link WorkflowTaskInvoker#validateTaskWiring(String, String, java.util.Collection)}
 * during <code>wireBpmn</code>.
 *
 * @param activityId The BPMN activity ID (the task element's <code>id</code>
 *          attribute), matched against <code>&#64;WorkflowTask(id = ...)</code>
 * @param taskDefinition The task definition (e.g. Camunda 8 job type, Camunda 7
 *          topic/delegate expression), matched against
 *          <code>&#64;WorkflowTask(taskDefinition = ...)</code>; may be
 *          <code>null</code> if the BPMS task carries none
 * @param optional Whether a matching <code>&#64;WorkflowTask</code> method is
 *          OPTIONAL: <code>false</code> for service-like tasks (an unmatched task
 *          fails the wiring validation with a guiding message), <code>true</code>
 *          for USER tasks - their notification handlers are optional
 *          (a user task without a handler is simply processed through forms/task
 *          lists), but a matching method is still marked as wired so the
 *          per-module unwired-methods check does not report it
 */
public record BpmnTaskSpec(
                           String activityId,
                           String taskDefinition,
                           boolean optional) {

  /**
   * A MANDATORY task spec (service-like tasks).
   *
   * @param activityId The BPMN activity ID
   * @param taskDefinition The task definition (may be <code>null</code>)
   */
  public BpmnTaskSpec(
      final String activityId,
      final String taskDefinition) {

    this(activityId, taskDefinition, false);

  }

  /**
   * An OPTIONAL task spec (user tasks - see {@link #optional()}).
   *
   * @param activityId The BPMN activity ID
   * @param taskDefinition The task definition (may be <code>null</code>)
   * @return The spec
   */
  public static BpmnTaskSpec userTask(
      final String activityId,
      final String taskDefinition) {

    return new BpmnTaskSpec(activityId, taskDefinition, true);

  }

}
