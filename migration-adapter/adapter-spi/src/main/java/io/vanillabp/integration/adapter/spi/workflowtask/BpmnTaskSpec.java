package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * Describes one task of an executable BPMN process which is to be wired to a
 * <code>&#64;WorkflowTask</code> method. The BPMS adapter passes it to
 * {@link WorkflowTaskWiring#validateTaskWiring(String, String, java.util.Collection)}
 * during <code>wireBpmn</code>.
 * <p>
 * {@link #name()} and {@link #multiInstanceElementsWithoutAnItem()} come LAST, although
 * both belong next to the activity id. Each was added later, to a record which every adapter
 * and every test already filled. Adding them at the end keeps the constructors those callers
 * use as they are.
 *
 * @param activityId The BPMN activity ID (the task element's <code>id</code>
 *          attribute), matched against <code>&#64;WorkflowTask(id = ...)</code>
 * @param taskDefinition The task definition (e.g. Camunda 8 job type, Camunda 7
 *          topic/delegate expression), matched against
 *          <code>&#64;WorkflowTask(taskDefinition = ...)</code>; may be
 *          <code>null</code> if the BPMS task carries none
 * @param optional Whether the element is a USER task: <code>true</code> for a user task,
 *          <code>false</code> for everything the BPMS hands to a worker. In a model which a
 *          deployment brings, it no longer changes anything. Every task of a claimed process
 *          needs a <code>&#64;WorkflowTask</code> method or the property
 *          <code>implemented-externally=true</code>, a user task as well (see
 *          {@link ImplementedExternally}). It still counts for an old version which only the
 *          BPMS still holds. A user task there needs no method, because nobody can change
 *          that model any more
 * @param name The <code>name</code> attribute of the BPMN element. It is what a modeller
 *          wrote on it, and what a person who reads a task list expects to see. May be
 *          <code>null</code>: an element needs no name, and an adapter which does not
 *          read one passes none. Nothing VanillaBP decides depends on it. It is carried
 *          because whoever reads the model reads the name anyway. Everyone else would
 *          have to parse the same bytes a second time to get it
 * @param multiInstanceElementsWithoutAnItem The ids of the multi-instance elements this task
 *          runs in which never name the value of an iteration. That is a Camunda 7 element
 *          without <code>camunda:elementVariable</code>, or a Camunda 8 one without
 *          <code>inputElement</code>. A handler which reads its item on such an element gets
 *          <code>null</code>. An adapter refuses that while it DEPLOYS a model. For a version
 *          which only the BPMS still holds, nobody could ask about it. The answer
 *          <code>null</code> means that this adapter does not read the shape. Then the
 *          question is not asked at all, instead of being answered by a guess (decision 38
 *          in the repository's DECISIONS.md). An empty list means that every element of
 *          the chain names its item. See decision 94 in the repository's
 *          DECISIONS.md
 * @param listener Whether this is a listener which the model carries on the element named
 *          by {@link #activityId()}, and not the element itself. Only a method which names
 *          its {@link #taskDefinition()} serves a listener. The reason:
 *          <code>&#64;WorkflowTask(id = ...)</code> names the element, and the element may
 *          have a method of its own. But a listener can be marked as served elsewhere by the
 *          element id as well as by its task definition. So one line covers every listener of
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
   * A task spec of an element, not of a listener. Every adapter wrote this before listeners
   * were told apart.
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
   * A task spec whose multi-instance shape is not read. An adapter which does not answer that
   * question writes this.
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
   * The task spec of a user task (see {@link #optional()}), with the name the modeller wrote
   * on the element.
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
   * @param taskDefinition The task definition the listener names, for example a job type
   * @param multiInstanceElementsWithoutAnItem See
   *          {@link #multiInstanceElementsWithoutAnItem()}, <code>null</code> if the
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
