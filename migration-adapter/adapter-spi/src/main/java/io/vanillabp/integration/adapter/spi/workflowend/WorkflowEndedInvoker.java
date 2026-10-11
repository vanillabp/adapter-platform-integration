package io.vanillabp.integration.adapter.spi.workflowend;

/**
 * The entry point of a BPMS adapter into VanillaBP for workflows which ended. The core
 * (the migration adapter) implements it, and the platform integration provides it to
 * adapters. Adapters use it twice:
 * <ol>
 * <li>While wiring: {@link #workflowEndedHandlerExists(String, String)}. A model must
 * not pay for a feature the application does not use. So a listener is attached only
 * where a <code>&#64;WorkflowEnded</code> method exists.</li>
 * <li>At runtime: {@link #workflowEnded(String, String, WorkflowEndedContext)} when
 * the BPMS reports the end. The core loads the workflow aggregate, calls the
 * application's method and saves the aggregate in a transaction.</li>
 * </ol>
 */
public interface WorkflowEndedInvoker {

  /**
   * Whether the application wants to be told about the end of workflows of the
   * given BPMN process.
   * <p>
   * The answer is <code>false</code> for a called process. That is a process which the
   * application declares only in <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code>,
   * and for which this start deployed a model. Its end is a step of the workflow which
   * called it, and that workflow has not ended yet. Some adapters report the cancelation
   * of an instance so that its open tasks are canceled. They may still do so for a called
   * process, see {@link #workflowEnded(String, String, WorkflowEndedContext)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return Whether the end of a workflow of that process has to be reported
   */
  boolean workflowEndedHandlerExists(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Tells the application that a workflow ended.
   * <p>
   * <strong>At-least-once:</strong> a notification which is delivered again calls the
   * method again. The core does not filter out repetitions, because it has nothing to
   * recognize them by. The method of the application has to be idempotent: running it
   * twice must do no harm.
   * <p>
   * A missing workflow aggregate is NOT an error here. An application may delete the
   * aggregate of a workflow which ended. A notification which arrives after that is
   * logged and skipped. It does not make the transaction of the BPMS fail.
   * <p>
   * <strong>A called process:</strong> if
   * {@link #workflowEndedHandlerExists(String, String)} answers <code>false</code> for a
   * process, the core only reports the tasks of that instance which it still thinks are
   * open as canceled.
   * It calls no <code>&#64;WorkflowEnded</code> method, keeps the election hint and
   * releases no delivery records, because the workflow goes on in the calling process.
   * The records of a called process are released when the workflow at the top ends.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The notification supplied by the adapter
   * @throws RuntimeException Whatever the method of the application threw. The
   *           transaction was rolled back, and the BPMS retries the way it always does
   */
  void workflowEnded(
      String workflowModuleId,
      String bpmnProcessId,
      WorkflowEndedContext context);

}
