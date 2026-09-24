package io.vanillabp.integration.adapter.spi;

import java.util.List;

/**
 * Which workflow an awareness probe is being asked about: the workflow module
 * and the BPMN processes the calling process service serves.
 * <p>
 * <b>Why the probes need it.</b> Until this record existed, a probe was told the
 * workflow-aggregate id and nothing else, so an adapter could only answer for the scope of
 * its own INSTANCE - "is this workflow one of mine" - and not for the scope of the CALL -
 * "is this the workflow you are asking about". The two differ wherever one adapter
 * instance serves several workflow modules, which is the ordinary case: aggregate ids are
 * unique per aggregate type and not across an application, so two modules whose aggregates
 * count from one both hold an id {@code 1}. Answering {@code ACTIVE} for the wrong one
 * costs nothing while a single BPMS is configured, and wins the election against the BPMS
 * really holding the workflow as soon as a migration is running.
 * <p>
 * <b>Why the processes are a list.</b> A {@code @WorkflowService} declares one primary
 * BPMN process and may declare {@code secondaryBpmnProcesses}; all of them run on the same
 * workflow aggregate, so an instance of any of them is a legitimate answer. Filtering by
 * the primary alone would drop the others, which would trade one defect for another.
 * <p>
 * The ids are the PLAIN ones, as the application models and configures them. What the BPMS
 * knows them by is the adapter's business (name-clash avoidance).
 *
 * <p>
 * Why a probe is told which workflow is meant instead of deciding from a key or an aggregate id is
 * decision 4 in the repository's DECISIONS.md.
 *
 * @param workflowModuleId The workflow module of the calling process service
 * @param bpmnProcessIds The plain BPMN process ids it serves, in no particular order:
 *          where the platform knows the processes of the module, the scope carries all of
 *          them, so no id in the list is the one the call is about
 */
public record WorkflowScope(
                            String workflowModuleId,
                            List<String> bpmnProcessIds) {

  /**
   * Copies the process ids, so a scope cannot change while it is being answered, and reads
   * a <code>null</code> list as an empty one. An adapter may therefore walk
   * {@link #bpmnProcessIds()} without checking it first.
   */
  public WorkflowScope {

    bpmnProcessIds = bpmnProcessIds == null
        ? List.of()
        : List.copyOf(bpmnProcessIds);

  }

  /**
   * The scope of a workflow service which declares no
   * <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code>, which is the ordinary
   * case.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The only BPMN process served
   * @return The scope of a process service serving one process
   */
  public static WorkflowScope of(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return new WorkflowScope(workflowModuleId, List.of(bpmnProcessId));

  }

}
