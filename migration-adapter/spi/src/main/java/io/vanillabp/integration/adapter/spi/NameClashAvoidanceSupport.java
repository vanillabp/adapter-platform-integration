package io.vanillabp.integration.adapter.spi;

import java.util.Collection;

/**
 * Builds the identifiers a BPMS sees, according to the workflow module's
 * {@link NameClashAvoidance} mode. Implemented ONCE by the core (so every adapter
 * scopes identically) and handed to every adapter by the platform integration.
 * <p>
 * An adapter uses it in two places:
 * <ol>
 * <li>in <code>prepareBpmn</code>, to rewrite the identifiers of its BPMN model -
 * only the adapter knows its model type;</li>
 * <li>at every runtime boundary, to translate identifiers on their way to the BPMS
 * (starting a workflow, correlating a message, throwing a BPMN error, querying the
 * viewer API) and on their way back (finding the
 * <code>&#64;WorkflowTask</code> handler of a delivered task).</li>
 * </ol>
 * In the modes {@link NameClashAvoidance#NONE} and
 * {@link NameClashAvoidance#BY_ADAPTER} every <code>scoped*</code> method returns
 * its input unchanged, so an adapter may call them unconditionally.
 * <p>
 * <b>Never build the strings yourself:</b> the separator and the composition are
 * the core's business, and the startup validation of colliding identifiers relies
 * on it.
 */
public interface NameClashAvoidanceSupport {

  /**
   * The separator between a prefix and the identifier it scopes. Two underscores:
   * legal in an XML {@code NCName} (so BPMN element ids may carry it, unlike e.g.
   * <code>#</code>) and unlikely to appear in an identifier by accident.
   */
  String SEPARATOR = "__";

  /**
   * The mode configured for the given workflow module (and, if given, workflow) of
   * the given adapter - the most specific configured value wins (workflow >
   * workflow module > adapter), the default is
   * {@link NameClashAvoidance#BY_ADAPTER}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code> to resolve the
   *          module's mode
   * @param adapterId The adapter ID
   * @return The mode, never <code>null</code>
   */
  NameClashAvoidance modeFor(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * The BPMN process ID as the BPMS knows it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The scoped ID, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedProcessId(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * An identifier scoped by the workflow module alone - message names, signal
   * names, error codes and escalation codes.
   *
   * @param workflowModuleId The workflow module ID
   * @param identifier The plain identifier (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped identifier, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedIdentifier(
      String workflowModuleId,
      String identifier,
      String adapterId);

  /**
   * A task definition, scoped by the workflow module AND - unless the application
   * switched it off
   * (<code>prefix-task-definitions-per-process: false</code>) - by the BPMN process
   * ID. Reusing one task implementation across processes is an anti-pattern, so
   * scoping per process is the default.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param taskDefinition The plain task definition (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped task definition, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition,
      String adapterId);

  /**
   * The inverse of {@link #scopedProcessId}: the plain BPMN process ID of what the
   * BPMS reported. Strips a KNOWN prefix (never "everything up to the first
   * separator"), so an identifier which does not carry the expected prefix is
   * returned unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The ID as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain BPMN process ID
   */
  String plainProcessId(
      String workflowModuleId,
      String scopedBpmnProcessId,
      String adapterId);

  /**
   * The inverse of {@link #scopedIdentifier}.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain identifier
   */
  String plainIdentifier(
      String workflowModuleId,
      String scopedIdentifier,
      String adapterId);

  /**
   * The inverse of {@link #scopedTaskDefinition}. The BPMN process ID is needed
   * because task definitions are scoped per process by default; pass the process
   * the task belongs to.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedTaskDefinition The task definition as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain task definition
   */
  String plainTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String scopedTaskDefinition,
      String adapterId);

  /**
   * The tenant a workflow module is deployed to under
   * {@link NameClashAvoidance#BY_ADAPTER} - the workflow module ID unless the
   * adapter configured an explicit name.
   * <p>
   * Adapters of a BPMS WITHOUT tenants must not call this; they reject
   * {@link NameClashAvoidance#BY_ADAPTER} at startup instead (see
   * {@link #validateNativeIsolationSupported}).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param adapterId The adapter ID
   * @param configuredTenantId The tenant name configured for the adapter or
   *          <code>null</code>
   * @return The tenant ID, or <code>null</code> if the mode does not use one
   */
  String tenantIdFor(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId,
      String configuredTenantId);

  /**
   * Fails with a guiding message if the mode resolved for the given workflow module
   * is {@link NameClashAvoidance#BY_ADAPTER} although the adapter's BPMS offers no
   * isolation mechanism of its own. Called by such an adapter while DEPLOYING a
   * workflow module (i.e. at startup) - the alternative would be silently deploying
   * every workflow module into one scope.
   * <p>
   * Note that {@link NameClashAvoidance#BY_ADAPTER} is the DEFAULT, so an adapter
   * without native isolation also fails when nothing is configured at all: the
   * developer has to choose actively.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module being deployed, or <code>null</code>
   *          to check every configured level of this adapter
   * @param bpmsDescription How the BPMS is named in the message, e.g. "the
   *          Process-Engine-API"
   * @throws IllegalStateException Naming the workflow module respectively the
   *           configuration levels, and the alternatives
   */
  void validateNativeIsolationSupported(
      String adapterId,
      String workflowModuleId,
      String bpmsDescription);

  /**
   * Fails with a guiding message if two of the given (workflow module, BPMN process
   * ID) pairs produce the SAME scoped process ID - the only way prefixing could
   * mix two workflows up. Called by the adapter once it knows the deployed
   * processes (the BPMN has to be read first).
   *
   * @param adapterId The adapter ID
   * @param deployedProcesses The (workflow module ID, BPMN process ID) pairs
   *          deployed to this adapter
   * @throws IllegalStateException Naming the colliding pairs and the fix
   */
  void validateNoCollidingProcessIds(
      String adapterId,
      Collection<DeployedProcess> deployedProcesses);

  /**
   * One deployed BPMN process, used by {@link #validateNoCollidingProcessIds}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   */
  record DeployedProcess(
                         String workflowModuleId,
                         String bpmnProcessId) {
  }

}
