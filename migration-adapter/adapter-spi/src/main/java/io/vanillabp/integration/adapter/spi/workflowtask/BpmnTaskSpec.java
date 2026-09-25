package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * Describes one task of an executable BPMN process which is to be wired to a
 * <code>&#64;WorkflowTask</code> method, supplied by the BPMS adapter to
 * {@link WorkflowTaskWiring#validateTaskWiring(String, String, java.util.Collection)}
 * during <code>wireBpmn</code>.
 * <p>
 * The {@link #name()} and {@link #multiInstanceElementsWithoutAnItem()} stand LAST although
 * both belong next to the activity id: each was added to a record whose other components
 * every adapter and every test already writes, and appending keeps the constructors those
 * callers use as they are.
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
 * @param name The <code>name</code> attribute of the BPMN element, what a modeller
 *          wrote on it and what a person reading a task list expects to see. May be
 *          <code>null</code>: an element needs no name, and an adapter which does not
 *          read one passes none. Nothing VanillaBP decides depends on it - it is
 *          carried because whoever reads the model reads it anyway, and everyone else
 *          would have to parse the same bytes a second time to get it
 * @param multiInstanceElementsWithoutAnItem The ids of the elements this task iterates in
 *          which never name the value of a round - a Camunda 7 element without
 *          <code>camunda:elementVariable</code>, a Camunda 8 one without
 *          <code>inputElement</code>. A handler reading its item on such an element gets
 *          <code>null</code>, which an adapter refuses while it DEPLOYS a model and which
 *          nobody could ask about a version a BPMS only still holds. Answering
 *          <code>null</code> means that this adapter does not read the shape, and the
 *          question is then not asked at all rather than answered by a guess (decision 38
 *          in the repository's DECISIONS.md); an empty list means that every element of
 *          the chain names its item. See decision &lt;pending: 556&gt; in the repository's
 *          DECISIONS.md
 */
public record BpmnTaskSpec(
                           String activityId,
                           String taskDefinition,
                           boolean optional,
                           String name,
                           java.util.List<String> multiInstanceElementsWithoutAnItem) {

  /**
   * A MANDATORY task spec (service-like tasks) whose BPMN name is not read.
   *
   * @param activityId The BPMN activity ID
   * @param taskDefinition The task definition (may be <code>null</code>)
   */
  public BpmnTaskSpec(
      final String activityId,
      final String taskDefinition) {

    this(activityId, taskDefinition, false, null, null);

  }

  /**
   * A task spec whose BPMN name is not read.
   *
   * @param activityId The BPMN activity ID
   * @param taskDefinition The task definition (may be <code>null</code>)
   * @param optional Whether a matching handler method is optional (see
   *          {@link #optional()})
   */
  public BpmnTaskSpec(
      final String activityId,
      final String taskDefinition,
      final boolean optional) {

    this(activityId, taskDefinition, optional, null, null);

  }

  /**
   * A task spec whose multi-instance shape is not read - what an adapter writes which does
   * not answer that question.
   *
   * @param activityId The BPMN activity ID
   * @param taskDefinition The task definition (may be <code>null</code>)
   * @param optional Whether a matching handler method is optional (see
   *          {@link #optional()})
   * @param name The BPMN <code>name</code> attribute (may be <code>null</code>)
   */
  public BpmnTaskSpec(
      final String activityId,
      final String taskDefinition,
      final boolean optional,
      final String name) {

    this(activityId, taskDefinition, optional, name, null);

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

    return new BpmnTaskSpec(activityId, taskDefinition, true, null, null);

  }

  /**
   * An OPTIONAL task spec (user tasks - see {@link #optional()}) carrying the name the
   * modeller wrote on the element.
   *
   * @param activityId The BPMN activity ID
   * @param taskDefinition The task definition (may be <code>null</code>)
   * @param name The BPMN <code>name</code> attribute (may be <code>null</code>)
   * @return The spec
   */
  public static BpmnTaskSpec userTask(
      final String activityId,
      final String taskDefinition,
      final String name) {

    return new BpmnTaskSpec(activityId, taskDefinition, true, name, null);

  }

}
