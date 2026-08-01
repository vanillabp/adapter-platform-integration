package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Map;

import io.vanillabp.spi.service.TaskEvent;

/**
 * All information a BPMS adapter supplies when a BPMN task is to be processed by a
 * <code>&#64;WorkflowTask</code> annotated method. The adapter builds one context per
 * task invocation (e.g. per Camunda 7 job execution or Camunda 8 job worker
 * delivery) and passes it to
 * {@link WorkflowTaskInvoker#invokeWorkflowTask(String, String, TaskInvocationContext)}.
 * The context is deliberately neutral: it carries only values, no BPMS types.
 */
public interface TaskInvocationContext {

  /**
   * The key used to resolve the <code>&#64;WorkflowTask</code> method: the BPMN
   * task's task definition (e.g. Camunda 8 job type, Camunda 7 topic/expression) or
   * the BPMN activity ID - handlers are registered under both
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code> respectively
   * <code>&#64;WorkflowTask(id = ...)</code>, defaulting to the method's name).
   *
   * @return The task definition or BPMN activity ID
   */
  String getTaskDefinition();

  /**
   * The workflow aggregate's ID in serialized form (the same String representation
   * used by the phase-two outbox, e.g. the Camunda 7 business key or the Camunda 8
   * aggregate-ID process variable). The core converts it back to the aggregate's ID
   * type and loads the aggregate.
   *
   * @return The serialized workflow-aggregate ID
   */
  String getWorkflowAggregateId();

  /**
   * The BPMS-side ID of this task instance, passed to parameters annotated with
   * <code>&#64;TaskId</code> and used to complete the task asynchronously.
   * <code>null</code> if the BPMS does not support asynchronous completion.
   *
   * @return The task instance's ID or <code>null</code>
   */
  default String getTaskId() {

    return null;

  }

  /**
   * The event being processed. BPMS adapters deliver {@link TaskEvent.Event#CREATED}
   * when a task is to be processed; {@link TaskEvent.Event#CANCELED} arrives with
   * the complete/cancel feature.
   *
   * @return The task event
   */
  default TaskEvent.Event getTaskEvent() {

    return TaskEvent.Event.CREATED;

  }

  /**
   * The value of a local variable mapped in the BPMN (input mapping), passed to
   * parameters annotated with <code>&#64;TaskParam</code>.
   *
   * @param name The name of the local variable
   * @return The value or <code>null</code> if not present
   */
  default Object getTaskParameter(
      final String name) {

    return null;

  }

  /**
   * The multi-instance context(s) the task executes in, keyed by the name of the
   * multi-instance element and ordered from the outermost to the innermost
   * execution. Empty if the task is not part of a multi-instance execution.
   * <p>
   * Adapters have to supply an ORDER-PRESERVING map (e.g.
   * {@link java.util.LinkedHashMap}).
   *
   * @return The multi-instance contexts, outermost first
   */
  default Map<String, MultiInstanceValue> getMultiInstances() {

    return Map.of();

  }

  /**
   * The version of the deployed BPMN process this task belongs to, matched against
   * <code>&#64;WorkflowTask(version = ...)</code>. <code>null</code> matches every
   * handler regardless of its version ranges.
   *
   * @return The process version or <code>null</code>
   */
  default String getProcessVersion() {

    return null;

  }

  /**
   * Whether the handler has to run within the transaction already active on the
   * calling thread (embedded BPMS sharing the application's transaction, e.g.
   * Camunda 7 on the default datasource) instead of a new transaction opened by the
   * core (remote BPMS delivering tasks on worker threads, e.g. Camunda 8).
   *
   * @return Whether to join the current transaction
   */
  default boolean runInCurrentTransaction() {

    return false;

  }

}
