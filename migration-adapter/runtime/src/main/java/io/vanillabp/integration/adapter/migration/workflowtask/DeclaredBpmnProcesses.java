package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;

/**
 * Which BPMN processes of a workflow module the application declares, and under which of
 * them a model was really deployed during this boot. The {@link WorkflowTaskRegistry}
 * implements it, because both are known there.
 * <p>
 * Renaming a BPMN process leaves the difference between the two behind. The new name arrives
 * with a model. The old one is only declared by
 * <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code>, and the BPMS still holds
 * it with running workflows. So an id under which nothing was deployed is not a defect. It
 * is the one case where every version which a BPMS holds is an older version.
 */
public interface DeclaredBpmnProcesses {

  /**
   * Whether that id is known from a declaration only, with no model deployed under it.
   * <p>
   * This case turns off the comparison against a deployed version. There is no newer
   * version, so every version which the BPMS still holds under that id is an older one.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Whether the application declares that id without bringing a model for it
   */
  boolean isDeclaredWithoutDeployment(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The ids of that module for which a model really arrived during this boot.
   * <p>
   * The message about an id under which nothing was deployed lists these ids. A typo is
   * easiest to see next to the ids which did reach a model.
   *
   * @param workflowModuleId The workflow module ID
   * @return The BPMN process ids of that module under which a model was deployed
   */
  Collection<String> deployedProcessesOf(
      String workflowModuleId);

}
