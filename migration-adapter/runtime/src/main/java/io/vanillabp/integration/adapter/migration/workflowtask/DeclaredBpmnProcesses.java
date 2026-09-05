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
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Whether the application declares that id without bringing a model for it
   */
  boolean isDeclaredWithoutDeployment(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * @param workflowModuleId The workflow module ID
   * @return The BPMN process ids of that module a model was deployed under
   */
  Collection<String> deployedProcessesOf(
      String workflowModuleId);

}
