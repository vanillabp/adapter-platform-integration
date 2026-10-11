package io.vanillabp.integration.adapter.spi.workflowstart;

import java.util.Collection;

/**
 * The entry point of a BPMS adapter into VanillaBP for the start of a workflow. The core
 * (the migration adapter) implements it, and the platform integration provides it to
 * adapters. Adapters use it twice:
 * <ol>
 * <li>During <code>wireBpmn</code>:
 * {@link #validateBpmsInitiatedStarts(String, String, Collection)} with EVERY start event
 * of the executable BPMN process. The core registers them. It reports a
 * <code>&#64;WorkflowStartedByBpms</code> method which serves a start event that does not
 * exist. And it refuses a process whose BPMS fires a start event by itself, but which has
 * no such method. Throwing from <code>wireBpmn</code> follows the
 * <code>deployment-failure</code> policy for adapters which are not first in priority.</li>
 * <li>At runtime:
 * {@link #startWorkflowByBpms(String, String, BpmsInitiatedStartContext)} for every start
 * the BPMS reports, also the starts of the application. The core decides from the state
 * of the workflow what that start is. If nobody started the workflow through VanillaBP,
 * the core builds and saves the workflow aggregate. It returns what the adapter has to
 * write back into the BPMS.</li>
 * </ol>
 * During <code>wireBpmn</code> an adapter also reports the messages which start a process,
 * through {@link #reportStartMessages(String, String, String, Collection)}. That is the
 * start the application asks for, not one the BPMS fires by itself. The core uses it to
 * check <code>ProcessService#startWorkflowByMessage</code>.
 * Some BPMS cannot tell VanillaBP about a start they do by themselves. Their adapter
 * implements none of this. Instead it fails the deployment of a process with such a start
 * event, with a guiding message, because that workflow could never get a workflow
 * aggregate.
 */
public interface BpmsInitiatedStartInvoker {

  /**
   * Registers the start events of a deployed BPMN process and validates the application
   * against them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param startEvents Every start event the process itself holds. An empty collection
   *          means the adapter found none. That is worth saying if a method serves this
   *          process
   * @throws IllegalStateException If a <code>&#64;WorkflowStartedByBpms</code>
   *           method serves a process or start event which does not exist (guiding
   *           message naming the method and the fix)
   */
  void validateBpmsInitiatedStarts(
      String workflowModuleId,
      String bpmnProcessId,
      Collection<BpmsInitiatedStartSpec> startEvents);

  /**
   * Reports the names of the messages which start a deployed BPMN process. They are read
   * from the model this adapter deploys while the application starts. It is called during
   * <code>wireBpmn</code>, once per executable BPMN process.
   * <p>
   * The core uses the names to check every call of
   * <code>ProcessService#startWorkflowByMessage</code> before phase one. A message which
   * does not start the process of that process service is refused. The exception names
   * the messages which do. Without the check, a message which starts ANOTHER process
   * would start that process, and the core would record the new workflow under the wrong
   * process.
   * <p>
   * The rules for the names:
   * <ul>
   * <li>Report the message start events the process itself holds. A message start event
   * of an event subprocess starts no workflow and is left out.</li>
   * <li>Report the PLAIN names, as the application passes them. If your adapter
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
   * Decides what a start which the BPMS reported means. If nobody started the workflow
   * through VanillaBP, it builds and saves the workflow aggregate. It reports what the
   * adapter has to write back.
   * <p>
   * <strong>Idempotency:</strong> a workflow which the BPMS already names, and which has
   * its workflow aggregate, is left alone. The result says so
   * ({@link BpmsInitiatedStartResult#created()}). So adapters may deliver a notification
   * more than once (a retried listener job, a recovered engine transaction), and no second
   * aggregate is created.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The notification supplied by the adapter
   * @return The aggregate's ID and the variables to write into the workflow
   * @throws IllegalStateException If no workflow service is registered for the BPMN
   *           process, if the BPMS gives the workflow a name which no workflow aggregate
   *           carries, or if nothing builds the aggregate of a workflow which reached the
   *           application without a name (guiding messages)
   * @throws RuntimeException Whatever a <code>&#64;WorkflowStartedByBpms</code>
   *           method threw. The transaction was rolled back, so no aggregate exists,
   *           and the BPMS retries the way it always does
   */
  BpmsInitiatedStartResult startWorkflowByBpms(
      String workflowModuleId,
      String bpmnProcessId,
      BpmsInitiatedStartContext context);

  /**
   * Whether the start of an instance of that BPMN process is the start of a workflow.
   * <p>
   * The answer is <code>false</code> for a called process. That is a process which the
   * application declares only in <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code>,
   * and for which this start deployed a model. Such an instance runs on the workflow aggregate
   * of the process which called it. So its start is a step of that workflow, not a workflow of
   * its own. The core builds no aggregate for it and writes no start row.
   * <p>
   * An adapter asks this before it reports a start through
   * {@link #startWorkflowByBpms(String, String, BpmsInitiatedStartContext)}. If the answer is
   * <code>false</code>, it reports nothing. If the start listener is written into the model,
   * the adapter asks while it wires the model, and leaves the listener out. If the listener is
   * attached at runtime and has other work to do as well, the adapter asks when it fires.
   * <p>
   * Sometimes the application declares an id as a secondary process but deploys no model for
   * it. That is the id a renamed process left behind. Its workflows were started under that id,
   * so for such an id the answer stays <code>true</code>.
   * <p>
   * The default answers <code>true</code>. Every process got that answer before this method
   * existed.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Whether the start of an instance of that process starts a workflow
   */
  default boolean startsAWorkflowOfItsOwn(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return true;

  }

}
