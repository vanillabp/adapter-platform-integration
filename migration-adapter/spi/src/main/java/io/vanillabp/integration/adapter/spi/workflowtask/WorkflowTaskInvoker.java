package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Collection;

/**
 * The BPMS adapter's entry point into VanillaBP's task processing, implemented by
 * the core (the migration adapter) and provided to adapters by the platform
 * integration. Adapters use it twice:
 * <ol>
 * <li>During <code>wireBpmn</code>:
 * {@link #validateTaskWiring(String, String, Collection)} with the executable BPMN
 * process' tasks - validates that every BPMN task has a
 * <code>&#64;WorkflowTask</code> method (the reverse direction runs per module via
 * {@link #validateNoUnwiredWorkflowTaskMethods}) and fails with guiding messages. Throwing from <code>wireBpmn</code> automatically honors
 * the <code>deployment-failure</code> policy for non-first-priority adapters.</li>
 * <li>At runtime: {@link #invokeWorkflowTask(String, String, TaskInvocationContext)}
 * whenever the BPMS delivers a task - the core resolves the handler, runs it in a
 * transaction (loading and saving the workflow aggregate) and maps the outcome (see
 * {@link WorkflowTaskOutcome}). Any exception other than
 * {@link io.vanillabp.spi.service.TaskException} rolls back the transaction and
 * propagates - the adapter applies the BPMS' retry semantics and MUST NOT complete
 * the task.</li>
 * </ol>
 */
public interface WorkflowTaskInvoker {

  /**
   * Validates that every given BPMN task is served by a
   * <code>&#64;WorkflowTask</code> method of the process' workflow service(s). All
   * unmatched tasks are collected and reported in ONE exception with guiding
   * messages. Additionally every matched method is marked as wired - the input for
   * {@link #validateNoUnwiredWorkflowTaskMethods(String)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks of the executable BPMN process to be wired
   * @throws IllegalStateException If a BPMN task has no matching method
   */
  void validateTaskWiring(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmnTaskSpec> tasks);

  /**
   * Validates - after ALL BPMN processes of a workflow module were wired - that
   * every <code>&#64;WorkflowTask</code> method matched a task of at least ONE of
   * the module's BPMN processes (a workflow service class may declare several
   * processes via {@code secondaryBpmnProcesses}, so a method unmatched in one
   * process may legitimately serve another - this check closes the second
   * direction the per-process {@link #validateTaskWiring} cannot decide). Called
   * by the adapter at the END of <code>deployResources</code>; throwing there
   * honors the <code>deployment-failure</code> policy.
   *
   * @param workflowModuleId The workflow module ID
   * @throws IllegalStateException Naming every method matching no task of any
   *           wired BPMN process, with the fix
   */
  void validateNoUnwiredWorkflowTaskMethods(
      String workflowModuleId);

  /**
   * Processes a BPMN task delivered by the BPMS: resolves the
   * <code>&#64;WorkflowTask</code> method registered for the context's task
   * definition (or activity ID), loads the workflow aggregate, invokes the method
   * with bound parameters and saves the aggregate - all within one transaction (a
   * new one, or the caller's if
   * {@link TaskInvocationContext#runInCurrentTransaction()}).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The invocation context supplied by the adapter
   * @return The outcome the adapter maps to the BPMS (complete /
   *         complete-with-BPMN-error / leave open)
   * @throws RuntimeException Whatever the handler threw (except
   *           <code>TaskException</code>) - the transaction was rolled back, BPMS
   *           retry semantics apply
   */
  WorkflowTaskOutcome invokeWorkflowTask(
      String workflowModuleId,
      String bpmnProcessId,
      TaskInvocationContext context);

  /**
   * Reads an attribute of the workflow aggregate identified by the given
   * serialized ID - used by embedded BPMS evaluating BPMN expressions against the
   * workflow aggregate (e.g. a Camunda 7 gateway condition
   * <code>${riskAcceptable}</code>): the attribute is resolved via getter
   * (<code>getX()</code>), boolean getter (<code>isX()</code>) or field access, in
   * this order. The aggregate is loaded within the CALLER's transaction (embedded
   * BPMS evaluate expressions inside an engine transaction) - callers without an
   * active transaction get whatever the persistence layer does outside
   * transactions.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param propertyName The attribute's name
   * @return The attribute's value or <code>null</code> if the BPMN process is
   *         unknown, the aggregate does not exist or it has no such attribute
   */
  Object resolveWorkflowAggregateProperty(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId,
      String propertyName);

  /**
   * Whether a <code>&#64;WorkflowTask</code> method is registered for the given
   * task definition (or BPMN activity ID) - used for OPTIONAL notifications
   * (user-task lifecycle events, story 24): the adapter checks before invoking so
   * a user task without a handler is silently skipped instead of raising the
   * guiding no-handler error meant for mandatory service tasks.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinitionOrActivityId The task definition or BPMN activity ID
   * @return Whether a matching method is registered
   */
  boolean workflowTaskHandlerExists(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinitionOrActivityId);

  /**
   * The name of the workflow aggregate's ID property for the given BPMN process -
   * used by remote BPMS without a business-key concept: they store the aggregate's
   * ID as a process variable named after the ID property (the start commands write
   * it, the task workers read it back).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The ID property's name
   * @throws IllegalStateException If the BPMN process is not served by any
   *           workflow service (guiding message)
   */
  String resolveWorkflowAggregateIdName(
      String workflowModuleId,
      String bpmnProcessId);

}
