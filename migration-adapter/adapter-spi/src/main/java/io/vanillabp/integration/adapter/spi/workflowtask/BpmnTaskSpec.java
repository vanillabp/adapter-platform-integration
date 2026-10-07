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
 * @param optional Whether the element is a USER task: <code>true</code> for a user task,
 *          <code>false</code> for everything the BPMS hands to a worker. In the model a
 *          deployment brings, it changes nothing any more - every task of a claimed process
 *          needs a <code>&#64;WorkflowTask</code> method or the property
 *          <code>implemented-externally=true</code>, a user task as well (see
 *          {@link ImplementedExternally}). It still counts for a version the BPMS only
 *          still holds: a user task there is not asked for a method, because nobody can
 *          change that model any more
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
 *          the chain names its item. See decision 94 in the repository's
 *          DECISIONS.md
 * @param listener Whether this is a listener the model carries on the element named by
 *          {@link #activityId()} rather than the element itself. A listener is served by a
 *          method naming its {@link #taskDefinition()} and by nothing else, because
 *          <code>&#64;WorkflowTask(id = ...)</code> names the element, and the element may
 *          have a method of its own. A listener is still marked as served elsewhere by the
 *          element id as well as by its task definition, so one line covers every listener of
 *          an element (see {@link ImplementedExternally})
 */
public record BpmnTaskSpec(
                           String activityId,
                           String taskDefinition,
                           boolean optional,
                           String name,
                           java.util.List<String> multiInstanceElementsWithoutAnItem,
                           boolean listener) {

  /**
   * A task spec of an element rather than of a listener - what every adapter wrote before
   * a listener was told apart.
   *
   * @param activityId The BPMN activity ID
   * @param taskDefinition The task definition (may be <code>null</code>)
   * @param optional Whether the element is a user task (see {@link #optional()})
   * @param name The BPMN <code>name</code> attribute (may be <code>null</code>)
   * @param multiInstanceElementsWithoutAnItem See
   *          {@link #multiInstanceElementsWithoutAnItem()}
   */
  public BpmnTaskSpec(
      final String activityId,
      final String taskDefinition,
      final boolean optional,
      final String name,
      final java.util.List<String> multiInstanceElementsWithoutAnItem) {

    this(activityId, taskDefinition, optional, name, multiInstanceElementsWithoutAnItem, false);

  }

  /**
   * The task spec of a service-like task whose BPMN name is not read.
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
   * @param optional Whether the element is a user task (see {@link #optional()})
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
   * @param optional Whether the element is a user task (see {@link #optional()})
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
   * The task spec of a user task (see {@link #optional()}).
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
   * The task spec of a user task (see {@link #optional()}) carrying the name the modeller
   * wrote on the element.
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

  /**
   * The task spec of a listener the model carries on an element (see {@link #listener()}).
   *
   * @param elementId The id of the element the listener sits on
   * @param taskDefinition The task definition the listener names, a job type say
   * @param multiInstanceElementsWithoutAnItem See
   *          {@link #multiInstanceElementsWithoutAnItem()}, <code>null</code> where the
   *          adapter does not read it
   * @return The spec
   */
  public static BpmnTaskSpec listener(
      final String elementId,
      final String taskDefinition,
      final java.util.List<String> multiInstanceElementsWithoutAnItem) {

    return new BpmnTaskSpec(elementId, taskDefinition, false, null, multiInstanceElementsWithoutAnItem, true);

  }

}
