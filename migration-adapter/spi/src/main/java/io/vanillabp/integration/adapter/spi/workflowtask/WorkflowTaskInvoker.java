package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Collection;

/**
 * The BPMS adapter's entry point into VanillaBP's task processing, implemented by
 * the core (the migration adapter) and provided to adapters by the platform
 * integration. Adapters use it twice:
 * <ol>
 * <li>During <code>wireBpmn</code>:
 * {@link #validateTaskWiring(String, String, Collection)} with the executable BPMN
 * process' tasks - validates both directions (every BPMN task has a
 * <code>&#64;WorkflowTask</code> method, every method matches a task) and fails
 * with guiding messages. Throwing from <code>wireBpmn</code> automatically honors
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
   * <code>&#64;WorkflowTask</code> method of the process' workflow service(s) and
   * that every such method matches one of the given tasks. All defects are
   * collected and reported in ONE exception with guiding messages.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks of the executable BPMN process to be wired
   * @throws IllegalStateException If the wiring is incomplete in either direction
   */
  void validateTaskWiring(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmnTaskSpec> tasks);

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

}
