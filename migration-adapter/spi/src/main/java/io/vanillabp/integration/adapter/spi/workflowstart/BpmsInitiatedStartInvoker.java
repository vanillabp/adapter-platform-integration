package io.vanillabp.integration.adapter.spi.workflowstart;

import java.util.Collection;

/**
 * The BPMS adapter's entry point into VanillaBP for workflows the BPMS starts on
 * its own - by a timer, signal or conditional start event. Implemented by the core
 * (the migration adapter) and provided to adapters by the platform integration.
 * Adapters use it twice:
 * <ol>
 * <li>During <code>wireBpmn</code>:
 * {@link #validateBpmsInitiatedStarts(String, String, Collection)} with the start
 * events of the executable BPMN process which fire without the application. The
 * core registers them and reports a
 * <code>&#64;WorkflowStartedByBpms</code> method serving a process (or a start
 * event) which has none - throwing from <code>wireBpmn</code> automatically honors
 * the <code>deployment-failure</code> policy for non-first-priority adapters.</li>
 * <li>At runtime:
 * {@link #startWorkflowByBpms(String, String, BpmsInitiatedStartContext)} when the
 * BPMS reports such a start. The core builds and saves the workflow aggregate in a
 * transaction and returns what the adapter has to write back into the BPMS.</li>
 * </ol>
 * An adapter whose BPMS cannot notify VanillaBP about its own starts does not
 * implement any of this; it fails the deployment of a process carrying such a start
 * event with a guiding message instead, because that workflow could never obtain a
 * workflow aggregate.
 */
public interface BpmsInitiatedStartInvoker {

  /**
   * Registers the BPMS-initiated start events of a deployed BPMN process and
   * validates the application against them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param startEvents The start events firing without the application - an empty
   *          collection is normal and means the process is started by the
   *          application only
   * @throws IllegalStateException If a <code>&#64;WorkflowStartedByBpms</code>
   *           method serves a process or start event which does not exist (guiding
   *           message naming the method and the fix)
   */
  void validateBpmsInitiatedStarts(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmsInitiatedStartSpec> startEvents);

  /**
   * Builds the workflow aggregate of a workflow the BPMS just started, saves it and
   * reports what the adapter has to write back.
   * <p>
   * <strong>Idempotency:</strong> an aggregate already existing under the derived ID
   * is reused instead of being replaced, and the result says so
   * ({@link BpmsInitiatedStartResult#created()}). Adapters may therefore deliver a
   * notification more than once (a retried listener job, a recovered engine
   * transaction) without creating a second aggregate.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The notification supplied by the adapter
   * @return The aggregate's ID and the variables to write into the workflow
   * @throws IllegalStateException If no workflow service is registered for the BPMN
   *           process, or if the aggregate can neither be instantiated nor be
   *           provided by the application (guiding messages)
   * @throws RuntimeException Whatever a <code>&#64;WorkflowStartedByBpms</code>
   *           method threw - the transaction was rolled back, so no aggregate
   *           exists and the BPMS' retry semantics apply
   */
  BpmsInitiatedStartResult startWorkflowByBpms(
      String workflowModuleId,
      String bpmnProcessId,
      BpmsInitiatedStartContext context);

}
