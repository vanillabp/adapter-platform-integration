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
   * @return When the workflow ended - the time reported by the BPMS, or the moment
   *         of the notification where it reports none
   */
  Instant getEndTime();

  /**
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

}
