package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * The BPMS adapter's entry point into VanillaBP's task processing AT RUNTIME,
 * implemented by the core (the migration adapter) and provided to adapters by the
 * platform integration. This is what an adapter's worker threads hold; what it calls
 * while it DEPLOYS is {@link WorkflowTaskWiring}.
 * <p>
 * The one call which matters is
 * {@link #invokeWorkflowTask(String, String, TaskInvocationContext)}, whenever the BPMS
 * delivers a task: the core resolves the handler, runs it in a transaction (loading and
 * saving the workflow aggregate) and maps the outcome (see {@link WorkflowTaskOutcome}).
 * Any exception other than {@link io.vanillabp.spi.service.TaskException} rolls back the
 * transaction and propagates - the adapter applies the BPMS' retry semantics and MUST NOT
 * complete the task.
 * <p>
 * The rest answers questions a delivery raises: which variable carries the aggregate's id,
 * which values the aggregate shares, and whether an OPTIONAL notification (a user-task
 * lifecycle event) has a handler at all.
 */
public interface WorkflowTaskInvoker {

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
   * The values shared with the BPMS, read within the transaction of the CALLER
   * instead of a new one - what an EMBEDDED engine needs.
   * <p>
   * An embedded engine invokes a <code>&#64;WorkflowTask</code> handler inside its own
   * transaction and completes the task in the same one, so the values have to be read
   * from the aggregate as it is NOW: reading in a new transaction would either see the
   * state before the handler ran or deadlock on the row the caller holds. A remote BPMS
   * is the opposite case and uses
   * {@link #syncedWorkflowAggregateValues(String, String, String, io.vanillabp.integration.adapter.spi.AggregateSyncMode)},
   * which reads after the commit.
   * <p>
   * Like its sibling this never throws: a failure to read yields an empty map, because
   * a task must be completable even when the values an operator would see cannot be
   * determined.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param adapterDefault What this adapter shares unless the application says
   *          otherwise
   * @return The values shared with the BPMS, empty if they cannot be determined
   */
  default java.util.Map<String, Object> syncedWorkflowAggregateValuesInCurrentTransaction(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    // the default reads like a remote BPMS would - the core overrides it and runs the read
    // in the caller's transaction. A test double of this SPI needs no answer of its own.
    return syncedWorkflowAggregateValues(
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId,
        adapterDefault);

  }

  /**
   * Whether the workflow aggregate of the given BPMN process HAS such an attribute -
   * answered from the aggregate's class, so no aggregate is loaded.
   *
   * @deprecated The shared values are pushed as process variables, so an embedded
   *             engine resolves expressions against them like every other BPMS. This
   *             method serves the MIGRATION fallback of the Camunda 7 adapter only (an
   *             application upgrading from version 1 has no variables in its running
   *             workflows yet, and version 1 also resolved attributes without a getter),
   *             and it is removed in 2.1 together with that fallback.
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param propertyName The attribute's name
   * @return Whether the aggregate class declares that attribute (getter, boolean getter
   *         or field); <code>false</code> if the BPMN process is unknown
   */
  @Deprecated(forRemoval = true)
  boolean workflowAggregateHasProperty(
      String workflowModuleId,
      String bpmnProcessId,
      String propertyName);

  /**
   * Reads an attribute of the workflow aggregate identified by the given serialized ID,
   * within the CALLER's transaction - getter, boolean getter or field, in this order.
   *
   * @deprecated The migration fallback, removed in 2.1 - see
   *             {@link #workflowAggregateHasProperty(String, String, String)}.
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param propertyName The attribute's name
   * @return The attribute's value or <code>null</code> if the BPMN process is unknown,
   *         the aggregate does not exist or it has no such attribute
   */
  @Deprecated(forRemoval = true)
  Object resolveWorkflowAggregateProperty(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId,
      String propertyName);

  /**
   * Whether a <code>&#64;WorkflowTask</code> method is registered for the given
   * task definition (or BPMN activity ID) - used for OPTIONAL notifications
   * (user-task lifecycle events): the adapter checks before invoking so
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
   * The values of a workflow aggregate SHARED WITH THE BPMS
   * ({@code @SyncWithBPMS}/{@code @NoSyncWithBPMS}, see
   * {@link io.vanillabp.integration.adapter.spi.WorkflowAggregateSync}) - for
   * adapters holding no aggregate at that moment. The typical caller is the task
   * worker of a REMOTE BPMS completing a task after a
   * <code>&#64;WorkflowTask</code> method ran: the engine only sees what the
   * adapter pushes, so a gateway right after the task would otherwise evaluate the
   * values of the last sync point.
   * <p>
   * The aggregate is loaded in its OWN transaction - the task's transaction is
   * committed at that point (the at-least-once order load-invoke-save-commit-
   * complete stays untouched). <b>This method never throws:</b> a BPMN process
   * which is not registered, a missing aggregate or a failing load are logged and
   * answered with an empty map, so the completion of the task still happens (with
   * the technical aggregate-ID variable only - which the adapter adds itself, it is
   * never part of these values).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param adapterDefault The adapter's default for aggregates carrying no
   *          annotation of their own
   * @return The shared values by attribute name - possibly empty, never
   *         <code>null</code>
   */
  java.util.Map<String, Object> syncedWorkflowAggregateValues(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId,
      io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault);

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
