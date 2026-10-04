package io.vanillabp.integration.adapter.spi.workflowstart;

import java.util.Collection;

/**
 * The BPMS adapter's entry point into VanillaBP for the start of a workflow. Implemented by
 * the core (the migration adapter) and provided to adapters by the platform integration.
 * Adapters use it twice:
 * <ol>
 * <li>During <code>wireBpmn</code>:
 * {@link #validateBpmsInitiatedStarts(String, String, Collection)} with EVERY start event
 * of the executable BPMN process. The core registers them, reports a
 * <code>&#64;WorkflowStartedByBpms</code> method serving a start event which does not
 * exist, and refuses a process whose BPMS fires a start event by itself and which has no
 * such method - throwing from <code>wireBpmn</code> automatically honors the
 * <code>deployment-failure</code> policy for non-first-priority adapters.</li>
 * <li>At runtime:
 * {@link #startWorkflowByBpms(String, String, BpmsInitiatedStartContext)} for every start
 * the BPMS reports, the application's own included. The core decides from the state of the
 * workflow what that start is, builds and saves the workflow aggregate where the workflow
 * turns out to be one nobody started through VanillaBP, and returns what the adapter has
 * to write back into the BPMS.</li>
 * </ol>
 * During <code>wireBpmn</code> an adapter also reports the messages which start a process,
 * through {@link #reportStartMessages(String, String, String, Collection)}. That is the
 * start the application asks for, not one the BPMS fires by itself, and the core uses it to
 * check <code>ProcessService#startWorkflowByMessage</code>.
 * An adapter whose BPMS cannot notify VanillaBP about a start it performs by itself does
 * not implement any of this; it fails the deployment of a process carrying such a start
 * event with a guiding message instead, because that workflow could never obtain a workflow
 * aggregate.
 */
public interface BpmsInitiatedStartInvoker {

  /**
   * Registers the start events of a deployed BPMN process and validates the application
   * against them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param startEvents Every start event the process itself holds - an empty collection
   *          means the adapter found none, which is worth saying where a method serves
   *          this process
   * @throws IllegalStateException If a <code>&#64;WorkflowStartedByBpms</code>
   *           method serves a process or start event which does not exist (guiding
   *           message naming the method and the fix)
   */
  void validateBpmsInitiatedStarts(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmsInitiatedStartSpec> startEvents);

  /**
   * Reports the names of the messages which start a deployed BPMN process, read from the
   * model this adapter deploys while the application starts. Called during
   * <code>wireBpmn</code>, once per executable BPMN process.
   * <p>
   * The core uses the names to check every call of
   * <code>ProcessService#startWorkflowByMessage</code> before phase one: a message which
   * does not start the process of that process service is refused with an exception
   * naming the messages which do. Without the check, a message which starts ANOTHER
   * process would start that one, and the core would record the new workflow under the
   * wrong process.
   * <p>
   * The rules for the names:
   * <ul>
   * <li>Report the message start events the process itself holds. A message start event
   * of an event subprocess starts no workflow and is left out.</li>
   * <li>Report the PLAIN names, as the application passes them. Where your adapter
   * prefixes identifiers to avoid name clashes, strip the prefix the way
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport#plainIdentifier}
   * does.</li>
   * <li>Report an empty collection for a process without a message start event. The core
   * then refuses every message for it.</li>
   * <li>Do not call this for a process where you cannot name every message start event,
   * for example because a name is an expression. The core then does not check that
   * process.</li>
   * </ul>
   * An adapter which never calls this is not checked at all. The core says so once per
   * BPMN process while the application starts, and the calls go to the BPMS as before.
   * <p>
   * The names are kept per adapter, because during a migration two adapters may deploy
   * the same process in different versions. The check asks the adapter which starts
   * the workflow, which is the first of the prioritized adapters.
   *
   * @param adapterId The ID of the adapter deploying the process
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param messageNames The plain names of the messages which start the process
   */
  void reportStartMessages(
      String adapterId,
      String workflowModuleId,
      String bpmnProcessId,
      Collection<String> messageNames);

  /**
   * Decides what a start the BPMS reported means, builds and saves the workflow aggregate
   * where the workflow turns out to be one nobody started through VanillaBP, and reports
   * what the adapter has to write back.
   * <p>
   * <strong>Idempotency:</strong> a workflow the BPMS already names and which has its
   * workflow aggregate is left alone, and the result says so
   * ({@link BpmsInitiatedStartResult#created()}). Adapters may therefore deliver a
   * notification more than once (a retried listener job, a recovered engine transaction)
   * without creating a second aggregate.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The notification supplied by the adapter
   * @return The aggregate's ID and the variables to write into the workflow
   * @throws IllegalStateException If no workflow service is registered for the BPMN
   *           process, if the BPMS names the workflow something no workflow aggregate
   *           carries, or if nothing builds the aggregate of a workflow which reached the
   *           application unnamed (guiding messages)
   * @throws RuntimeException Whatever a <code>&#64;WorkflowStartedByBpms</code>
   *           method threw - the transaction was rolled back, so no aggregate
   *           exists and the BPMS' retry semantics apply
   */
  BpmsInitiatedStartResult startWorkflowByBpms(
      String workflowModuleId,
      String bpmnProcessId,
      BpmsInitiatedStartContext context);

}
