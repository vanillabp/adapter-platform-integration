package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * The entry point of a BPMS adapter into the task processing of VanillaBP AT RUNTIME. The
 * core (the migration adapter) implements it, and the platform integration provides it to
 * adapters. The worker threads of an adapter hold it. While an adapter DEPLOYS, it calls
 * {@link WorkflowTaskWiring} instead.
 * <p>
 * The one call which matters is
 * {@link #invokeWorkflowTask(String, String, TaskInvocationContext)}. An adapter makes it
 * whenever the BPMS delivers a task. The core finds the handler, runs it in a transaction
 * (it loads and saves the workflow aggregate) and maps the outcome (see
 * {@link WorkflowTaskOutcome}). Any exception other than
 * {@link io.vanillabp.spi.service.TaskException} rolls back the transaction and is passed
 * on. The adapter then lets the BPMS retry the way it always does, and it MUST NOT
 * complete the task.
 * <p>
 * The other methods answer questions a delivery raises: which variable carries the id of
 * the aggregate, which values the aggregate shares, and whether a notification (an event
 * in the life of a user task) has a handler at all.
 * <p>
 * <b>The BPMN process of a delivery</b> is the process the task belongs to. For a task of a
 * called process (a call activity), that is the called process. It is not the process at
 * the top, which the workflow aggregate was started with. The core binds methods per
 * workflow service class and per process the class declares. That includes the called one,
 * through <code>secondaryBpmnProcesses</code>, and each declaration has a version range of
 * its own. A class serves every process it declares. So the id of the process at the top
 * may find the same method. But then the core applies the range declared for the process
 * at the top, and the delivery is recorded under the wrong process.
 */
public interface WorkflowTaskInvoker {

  /**
   * Processes a BPMN task delivered by the BPMS. It finds the
   * <code>&#64;WorkflowTask</code> method registered for the task definition (or
   * activity ID) of the context, loads the workflow aggregate, calls the method with
   * its parameters bound and saves the aggregate. All of that happens in one
   * transaction: a new one, or the caller's one if
   * {@link TaskInvocationContext#runInCurrentTransaction()}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process the task belongs to. For a task of a called
   *          process, that is the called process, not the one at the top
   * @param context The invocation context supplied by the adapter
   * @return The outcome the adapter maps to the BPMS (complete /
   *         complete-with-BPMN-error / leave open)
   * @throws RuntimeException Whatever the handler threw (except
   *           <code>TaskException</code>). The transaction was rolled back, and the
   *           BPMS retries the way it always does
   */
  WorkflowTaskOutcome invokeWorkflowTask(
      String workflowModuleId,
      String bpmnProcessId,
      TaskInvocationContext context);

  /**
   * Reports the tasks which the BPMS does not have any more. An adapter calls it after it
   * handed a delivery over. So an application on a BPMS which reports no cancellation
   * learns about them anyway.
   *
   * <h4>The principle</h4>
   *
   * An application on a remote BPMS learns nothing by itself. Whenever the BPMS hands it a
   * job, this call looks at the other tasks which VanillaBP thinks are open in the SAME
   * workflow. It asks the given probe whether they still exist. It reports the ones which
   * are gone to the application as
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED}. The scope is the one workflow
   * the wake-up belongs to, never the tree of workflows an aggregate owns.
   * <p>
   * This blindness is not a trait of one particular BPMS. An embedded engine which runs in
   * the same JVM says for each element what it took away, and it needs none of this. An
   * adapter of such a BPMS just does not call it.
   *
   * <h4>What it costs</h4>
   *
   * One read of the delivery log, which is an indexed read of about 0.01 ms. Then one round
   * trip per other open task, one after the other, on the thread which calls this. In the
   * normal case there are no round trips, because most workflows have no second open task.
   * A workflow with more open tasks than
   * <code>vanillabp.delivery.max-open-tasks-checked</code> spreads its probes over several
   * wake-ups, oldest record first. It does not hold an execution slot for all of them.
   * <p>
   * A BPMN process without any asynchronous task pays nothing. The core knows that from the
   * deployment and never reads the log for it.
   * <p>
   * An application which does not want the new calls switches them off with
   * <code>vanillabp.delivery.check-open-tasks-on-delivery</code>.
   *
   * <h4>What it does not promise</h4>
   *
   * Sometimes a workflow reaches a timer or a message wait after a boundary event. That
   * creates no job, so nothing wakes the application up, and the cancellation waits. Three
   * things catch it later: the next job of that workflow, the end of the workflow
   * ({@link io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext#getWorkflowId()}),
   * and the next operation which names the task.
   * <p>
   * The default does nothing, so a test double of this SPI keeps compiling.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID of the delivery which woke the application up
   * @param wakeUp The context of that delivery. It names the adapter, the workflow, the
   *          aggregate, and the task which must not be probed, because it is being worked
   *          on
   * @param probe What asks the BPMS about one task
   */
  default void reportTasksTheBpmsNoLongerHas(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext wakeUp,
      final OpenTaskProbe probe) {

  }

  /**
   * The values shared with the BPMS, read in the transaction of the CALLER, not in a new
   * one. An EMBEDDED engine needs this.
   * <p>
   * An embedded engine calls a <code>&#64;WorkflowTask</code> handler in its own transaction
   * and completes the task in the same one. So the values have to be read from the
   * aggregate as it is NOW. A read in a new transaction would either see the state before
   * the handler ran, or deadlock on the row the caller holds. A remote BPMS is the opposite
   * case and uses
   * {@link #syncedWorkflowAggregateValues(String, String, String, io.vanillabp.integration.adapter.spi.AggregateSyncMode)},
   * which reads after the commit.
   * <p>
   * Like its sibling, this never throws. If the read fails, the answer is an empty map. A
   * task must be completable even if the values an operator would see cannot be found
   * out.
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

    // the default reads the way a remote BPMS would. The core overrides it and runs the read
    // in the caller's transaction. A test double of this SPI needs no answer of its own.
    return syncedWorkflowAggregateValues(
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId,
        adapterDefault);

  }

  /**
   * Whether the workflow aggregate of the given BPMN process HAS such an attribute. The
   * answer comes from the class of the aggregate, so no aggregate is loaded.
   *
   * @deprecated The shared values are pushed as process variables. So an embedded engine
   *             evaluates expressions against them, like every other BPMS. This method
   *             only serves the MIGRATION fallback of the Camunda 7 adapter: an
   *             application which upgrades from version 1 has no variables in its running
   *             workflows yet, and version 1 also read attributes without a getter. The
   *             method goes away together with that fallback. No version is named for the
   *             removal, so ask the VanillaBP team if you need a date.
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
   * Reads an attribute of the workflow aggregate with the given serialized ID, in the
   * CALLER's transaction. It tries the getter, the boolean getter and the field, in this
   * order.
   *
   * @deprecated The migration fallback, which will be removed. See
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
   * Whether a <code>&#64;WorkflowTask</code> method is registered for the given task
   * definition (or BPMN activity ID). It is used for notifications (events in the life of a
   * user task). The adapter checks before it calls the method. So a user task without a
   * handler is skipped silently. It does not raise the error about a missing handler, which
   * is meant for service tasks. Such a user task passed the startup, because the
   * application marked it as served by something else (<code>implemented-externally</code>).
   * <p>
   * A task skipped here is finished by the ADAPTER and leaves no delivery record. That is
   * why the delivery log knows the open work of the application, not the open work of the
   * workflow (see decision 54 in the repository's DECISIONS.md).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process the task belongs to. For a task of a called
   *          process, that is the called process, not the one at the top
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
   * {@link io.vanillabp.integration.adapter.spi.WorkflowAggregateSync}). It is for
   * adapters which hold no aggregate at that moment. The typical caller is the task
   * worker of a REMOTE BPMS, which completes a task after a
   * <code>&#64;WorkflowTask</code> method ran. The engine only sees what the adapter
   * pushes. Without this, a gateway right after the task would evaluate the values of
   * the last sync point.
   * <p>
   * The aggregate is loaded in its OWN transaction, because the transaction of the task
   * is committed at that point. The at-least-once order load-invoke-save-commit-complete
   * stays as it is. <b>This method never throws.</b> A BPMN process which is not
   * registered, a missing aggregate or a failing load are logged and answered with an
   * empty map. So the task is still completed, with the technical variable of the
   * aggregate ID only. The adapter adds that variable itself, and it is never part of
   * these values.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param adapterDefault The adapter's default for aggregates carrying no
   *          annotation of their own
   * @return The shared values by attribute name. Possibly empty, never
   *         <code>null</code>
   */
  java.util.Map<String, Object> syncedWorkflowAggregateValues(
      String workflowModuleId,
      String bpmnProcessId,
      String workflowAggregateId,
      io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault);

  /**
   * The name of the ID property of the workflow aggregate for the given BPMN process.
   * Remote BPMS without a business key use it. They store the ID of the aggregate as a
   * process variable named after the ID property. The start commands write it, and the
   * task workers read it back.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The ID property's name
   * @throws IllegalStateException If no workflow service serves the BPMN process
   *           (with a message which says what to do)
   */
  String resolveWorkflowAggregateIdName(
      String workflowModuleId,
      String bpmnProcessId);
}
