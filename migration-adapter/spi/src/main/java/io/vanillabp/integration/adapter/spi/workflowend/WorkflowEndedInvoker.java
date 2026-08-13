package io.vanillabp.integration.adapter.spi.workflowend;

/**
 * The BPMS adapter's entry point into VanillaBP for workflows which ended.
 * Implemented by the core (the migration adapter) and provided to adapters by the
 * platform integration. Adapters use it twice:
 * <ol>
 * <li>While wiring: {@link #workflowEndedHandlerExists(String, String)} - a model
 * must not pay for a feature the application does not use, so a listener is
 * attached only where a <code>&#64;WorkflowEnded</code> method exists.</li>
 * <li>At runtime: {@link #workflowEnded(String, String, WorkflowEndedContext)} when
 * the BPMS reports the end. The core loads the workflow aggregate, calls the
 * application's method and saves the aggregate in a transaction.</li>
 * </ol>
 */
public interface WorkflowEndedInvoker {

  /**
   * Whether the application wants to be told about the end of workflows of the
   * given BPMN process.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return Whether a <code>&#64;WorkflowEnded</code> method is registered
   */
  boolean workflowEndedHandlerExists(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Tells the application that a workflow ended.
   * <p>
   * <strong>At-least-once:</strong> a redelivered notification calls the method
   * again - the core does not deduplicate, because it has nothing to deduplicate
   * by; the application's method is expected to be idempotent.
   * <p>
   * A missing workflow aggregate is NOT an error here: an application may delete
   * the aggregate of a workflow which ended, and a notification arriving after that
   * is logged and skipped rather than failing the BPMS' transaction.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The notification supplied by the adapter
   * @throws RuntimeException Whatever the application's method threw - the
   *           transaction was rolled back and the BPMS' retry semantics apply
   */
  void workflowEnded(
      String workflowModuleId,
      String bpmnProcessId,
      WorkflowEndedContext context);

}
