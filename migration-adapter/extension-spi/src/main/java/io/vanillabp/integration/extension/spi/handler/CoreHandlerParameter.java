package io.vanillabp.integration.extension.spi.handler;

/**
 * The kinds of parameter VanillaBP binds itself. A {@link HandlerContract} names the ones
 * its methods may ask for. A parameter of a kind the contract left out is refused at
 * startup, and the message names what may stand there instead.
 * <p>
 * The parameters for processing a task (<code>&#64;TaskId</code>,
 * <code>&#64;TaskEvent</code>) are missing on purpose. They belong to a BPMN task which
 * VanillaBP completes, and a handler of an extension is not such a task.
 */
public enum CoreHandlerParameter {

  /**
   * The workflow aggregate, taken by its type without an annotation. A
   * <code>&#64;WorkflowTask</code> method takes it the same way.
   */
  WORKFLOW_AGGREGATE,

  /**
   * A process variable named by <code>&#64;TaskParam</code>. It is converted to the type
   * of the parameter, the same way as for a <code>&#64;WorkflowTask</code> method. The
   * values come from {@link HandlerCall#getVariables()}.
   */
  TASK_PARAM,

  /**
   * The multi-instance parameters <code>&#64;MultiInstanceElement</code>,
   * <code>&#64;MultiInstanceIndex</code> and <code>&#64;MultiInstanceTotal</code>,
   * bound from {@link HandlerCall#getMultiInstances()}. Element resolvers
   * (<code>resolverBean</code>) are looked up as beans of the application, as they
   * are for a workflow task.
   */
  MULTI_INSTANCE

}
