package io.vanillabp.integration.adapter.spi.workflowend;

import java.time.Instant;

import io.vanillabp.spi.service.WorkflowEnd;

/**
 * All information a BPMS adapter supplies when a workflow ended. The adapter builds
 * one context per notification (e.g. a Camunda 7 process-end execution listener or
 * a Camunda 8 end execution-listener job) and passes it to
 * {@link WorkflowEndedInvoker#workflowEnded(String, String, WorkflowEndedContext)}.
 * The context is deliberately neutral: it carries only values, no BPMS types.
 */
public interface WorkflowEndedContext {

  /**
   * The workflow aggregate's ID in serialized form (the same String representation
   * used everywhere else, e.g. the Camunda 7 business key or the Camunda 8
   * aggregate-ID process variable).
   *
   * @return The serialized workflow-aggregate ID
   */
  String getWorkflowAggregateId();

  /**
   * How the workflow ended, as far as this BPMS reports it. An adapter which cannot
   * tell a cancellation from a regular end reports
   * {@link WorkflowEnd.Kind#COMPLETED} and says so in its documentation.
   *
   * @return The kind of end
   */
  WorkflowEnd.Kind getKind();

  /**
   * When the workflow ended, handed to a <code>&#64;WorkflowEnded</code> method as
   * {@link WorkflowEnd#time()}. Where the BPMS reports no time of its own, the moment of
   * the notification stands in for it, so the value says when VanillaBP heard rather than
   * when the engine ended.
   *
   * @return When the workflow ended - the time reported by the BPMS, or the moment
   *         of the notification where it reports none
   */
  Instant getEndTime();

  /**
   * Which end event was reached, which is what picks the
   * <code>&#64;WorkflowEnded</code> method: a method naming an end event serves that one
   * alone, a method naming none serves every end. An adapter whose BPMS does not report
   * the event therefore reaches only the methods which name none.
   *
   * @return The BPMN id of the end event reached, or <code>null</code> where the
   *         BPMS does not report it
   */
  default String getEndEventId() {

    return null;

  }

  /**
   * The version of the deployed BPMN process definition the ended workflow ran on, as
   * the BPMS counts it. It is matched against
   * <code>&#64;WorkflowEnded(version = ...)</code> like a task's version is matched
   * against <code>&#64;WorkflowTask(version = ...)</code> - see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getProcessVersion()}.
   * <code>null</code> matches every method regardless of its version ranges.
   *
   * @return The process version or <code>null</code>
   */
  default String getProcessVersion() {

    return null;

  }

  /**
   * Whether the method has to run within the transaction already active on the
   * calling thread (an embedded BPMS ending the workflow in its own transaction,
   * e.g. Camunda 7) instead of a new transaction opened by the core (a remote BPMS
   * delivering the notification on a worker thread, e.g. Camunda 8).
   *
   * @return Whether to join the current transaction
   */
  default boolean runInCurrentTransaction() {

    return false;

  }


  /**
   * The ID of the adapter delivering this notification of the workflow's end. A delivery PROVES that this BPMS holds
   * the workflow, which is why VanillaBP records the association: the next operation
   * on that workflow probes the recorded adapter first, and an eventually consistent
   * BPMS which does not report the workflow yet gets a second look instead of an
   * immediate failure (see {@code WorkflowVisibilityDelay}).
   * <p>
   * The default is <code>null</code>, which records nothing - an adapter written
   * before this existed keeps working unchanged.
   *
   * @return The adapter's ID or <code>null</code>
   */
  default String getAdapterId() {

    return null;

  }


  /**
   * The BPMS' own id of the workflow which ended: the process instance key of Camunda 8,
   * the process instance id of an embedded engine, whatever the BPMS talks about a running
   * instance in. The same value and the same word as
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getWorkflowId()},
   * which is what the core wrote into the record of every task of that workflow.
   *
   * <h4>What naming it buys</h4>
   *
   * The core reads the tasks it still believes are open in that workflow and reports every
   * one of them to the application as
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED}, before it calls
   * <code>&#64;WorkflowEnded</code>. A workflow which ended has nothing open any more, so
   * this is knowledge rather than a guess - and on a BPMS which cannot say per element what
   * it took away, it is the only way the application hears about those tasks at all.
   * <p>
   * The KIND of the end does not decide it. A terminate end event and an interrupting event
   * subprocess end a Camunda 8 instance as {@link WorkflowEnd.Kind#COMPLETED} while both of
   * them can take an open task away on the way out, so reading the kind first would skip
   * exactly those. Why is decision 73 in the repository's DECISIONS.md.
   *
   * <h4>Which adapter leaves it empty on purpose</h4>
   *
   * An adapter whose BPMS cancels each element by itself. Camunda 7 fires an END execution
   * listener per element, process termination included, so it delivers
   * <code>CANCELED</code> out of the engine's own cancellation transaction - a derivation on
   * top would report the same task twice. Such an adapter names no workflow here, and says
   * so in its documentation.
   * <p>
   * The default is <code>null</code>, which derives nothing: an adapter written before this
   * existed reports the end exactly as it does today.
   *
   * @return The workflow's id in the BPMS or <code>null</code>
   */
  default String getWorkflowId() {

    return null;

  }

  /**
   * The business key the BPMS keeps for the ended workflow of its own accord, where the
   * BPMS has such a thing at all. The contract is the one of
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getBusinessKey()}:
   * a business key is only ever a copy of {@link #getWorkflowAggregateId()}, a key
   * which says something else makes the workflow carry two identities at once, and the
   * core refuses the notification rather than picking one of them.
   * <p>
   * The default is <code>null</code>, which means "this BPMS keeps no business key" and
   * contradicts nothing.
   *
   * @return The BPMS' own business key of this workflow or <code>null</code>
   */
  default String getBusinessKey() {

    return null;

  }

}
