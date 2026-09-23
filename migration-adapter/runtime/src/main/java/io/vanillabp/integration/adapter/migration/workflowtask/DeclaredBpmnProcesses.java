package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;

/**
 * Which BPMN processes of a workflow module the application declares, and which of them a
 * model was really deployed under during this boot - implemented by the
 * {@link WorkflowTaskRegistry}, which is where both are known.
 * <p>
 * The difference between the two is what renaming a BPMN process leaves behind: the new
 * name arrives with a model, the old one is declared by
 * <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code> alone, and the BPMS still
 * holds it with the workflows running on it. So an id nothing was deployed under is not a
 * defect, and it is the one case where every version a BPMS holds is an older version.
 */
public interface DeclaredBpmnProcesses {

  /**
   * Whether that id is known from a declaration only, with no model deployed under it.
   * <p>
   * This is the case which turns off the comparison against a deployed version: there is
   * no newer version, so every version the BPMS still holds under that id is an older one.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Whether the application declares that id without bringing a model for it
   */
  boolean isDeclaredWithoutDeployment(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The ids of that module which a model really arrived for during this boot.
   * <p>
   * The message about an id nothing was deployed under lists these ids, because a typo is
   * easiest to see next to the ids which did reach a model.
   *
   * @param workflowModuleId The workflow module ID
   * @return The BPMN process ids of that module a model was deployed under
   */
  Collection<String> deployedProcessesOf(
      String workflowModuleId);

}
