package io.vanillabp.integration.adapter.spi.workflowend;

import java.time.Instant;

import io.vanillabp.spi.service.WorkflowEnd;

/**
 * All information a BPMS adapter supplies when a workflow has ended. The adapter builds
 * one context per notification (e.g. a Camunda 7 process-end execution listener or
 * a Camunda 8 end execution-listener job) and passes it to
 * {@link WorkflowEndedInvoker#workflowEnded(String, String, WorkflowEndedContext)}.
 * The context is neutral on purpose. It carries only values, no BPMS types.
 */
public interface WorkflowEndedContext {

  /**
   * The ID of the workflow aggregate in serialized form. It is the same String used
   * everywhere else, e.g. the Camunda 7 business key or the Camunda 8 aggregate-ID
   * process variable.
   *
   * @return The serialized workflow-aggregate ID
   */
  String getWorkflowAggregateId();

  /**
   * How the workflow ended, as far as this BPMS reports it. If an adapter cannot tell
   * a cancellation from a regular end, it reports {@link WorkflowEnd.Kind#COMPLETED} and
   * says so in its documentation.
   *
   * @return The kind of end
   */
  WorkflowEnd.Kind getKind();

  /**
   * When the workflow ended. A <code>&#64;WorkflowEnded</code> method gets it as
   * {@link WorkflowEnd#time()}. If the BPMS reports no time of its own, the moment of the
   * notification is used instead. Then the value says when VanillaBP heard about the end,
   * not when the engine ended the workflow.
   *
   * @return When the workflow ended: the time reported by the BPMS, or the moment of the
   *         notification if it reports none
   */
  Instant getEndTime();

  /**
   * Which end event was reached. This picks the <code>&#64;WorkflowEnded</code> method.
   * A method which names an end event serves only that one. A method which names none
   * serves every end. So an adapter whose BPMS does not report the event reaches only the
   * methods which name none.
   *
   * @return The BPMN id of the end event reached, or <code>null</code> if the BPMS does
   *         not report it
   */
  default String getEndEventId() {

    return null;

  }

  /**
   * The version of the deployed BPMN process definition the ended workflow ran on, as
   * the BPMS counts it. It is matched against
   * <code>&#64;WorkflowEnded(version = ...)</code>, just like the version of a task is
   * matched against <code>&#64;WorkflowTask(version = ...)</code>. See
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getProcessVersion()}.
   * <code>null</code> is served only by a method which names no version range.
   *
   * @return The process version or <code>null</code>
   */
  default String getProcessVersion() {

    return null;

  }

  /**
   * Whether the method has to run in the transaction which is already active on the
   * calling thread. That is the case for an embedded BPMS which ends the workflow in its
   * own transaction (e.g. Camunda 7). Otherwise the core opens a new transaction. That is
   * the case for a remote BPMS which delivers the notification on a worker thread (e.g.
   * Camunda 8).
   *
   * @return Whether to join the current transaction
   */
  default boolean runInCurrentTransaction() {

    return false;

  }


  /**
   * The ID of the adapter which delivers this notification about the end of the
   * workflow. A delivery PROVES that this BPMS holds the workflow, so VanillaBP records
   * the link. The next operation on that workflow asks the recorded adapter first. A
   * BPMS which is only eventually consistent may not report the workflow yet. It then
   * gets a second look instead of an immediate failure (see
   * {@code WorkflowVisibilityDelay}).
   * <p>
   * The default is <code>null</code>, which records nothing. So an adapter written before
   * this method existed keeps working unchanged.
   *
   * @return The adapter's ID or <code>null</code>
   */
  default String getAdapterId() {

    return null;

  }


  /**
   * The own id of the BPMS for the workflow which ended. That is the process instance key
   * of Camunda 8, the process instance id of an embedded engine, or whatever the BPMS
   * calls a running instance. It is the same value and the same word as
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getWorkflowId()}.
   * The core wrote that value into the record of every task of that workflow.
   *
   * <h4>What naming it buys</h4>
   *
   * The core reads the tasks of that workflow which it still thinks are open. It reports
   * each of them to the application as
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED}, before it calls
   * <code>&#64;WorkflowEnded</code>. A workflow which ended has nothing open any more, so
   * this is knowledge, not a guess. Some BPMS cannot say per element what they took away.
   * There this is the only way the application hears about those tasks at all.
   * <p>
   * The KIND of the end does not decide it. A terminate end event and an interrupting event
   * subprocess end a Camunda 8 instance as {@link WorkflowEnd.Kind#COMPLETED}. But both of
   * them can take an open task away on the way out. If the core read the kind first, it
   * would skip exactly those tasks. Why is decision 73 in the repository's DECISIONS.md.
   *
   * <h4>Which adapter leaves it empty on purpose</h4>
   *
   * An adapter whose BPMS cancels each element by itself. Camunda 7 fires an END execution
   * listener per element, also when the process is terminated. So it delivers
   * <code>CANCELED</code> from the cancellation transaction of the engine itself. If the core
   * worked it out on top, it would report the same task twice. Such an adapter names no
   * workflow here, and says so in its documentation.
   * <p>
   * The default is <code>null</code>, which works nothing out. An adapter written before this
   * method existed reports the end exactly as it does today.
   *
   * @return The workflow's id in the BPMS or <code>null</code>
   */
  default String getWorkflowId() {

    return null;

  }

  /**
   * The business key which the BPMS itself keeps for the ended workflow, if the BPMS has
   * such a thing at all. The contract is the one of
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getBusinessKey()}.
   * A business key is only ever a copy of {@link #getWorkflowAggregateId()}. A key which
   * says something else gives the workflow two identities at once. Then the core refuses
   * the notification and does not pick one of them.
   * <p>
   * The default is <code>null</code>. It means "this BPMS keeps no business key" and
   * contradicts nothing.
   *
   * @return The BPMS' own business key of this workflow or <code>null</code>
   */
  default String getBusinessKey() {

    return null;

  }

}
