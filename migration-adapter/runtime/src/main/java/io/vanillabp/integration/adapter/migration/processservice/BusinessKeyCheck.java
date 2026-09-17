package io.vanillabp.integration.adapter.migration.processservice;

/**
 * Holds one rule: a business key counts only where it carries the workflow aggregate's
 * id.
 * <p>
 * VanillaBP names a workflow by its workflow aggregate and by nothing else. A BPMS which
 * keeps a business key of its own (Camunda 7 always, Camunda 8 from cluster 8.10) gets
 * that id written into it wherever VanillaBP starts the workflow. A workflow started past
 * VanillaBP can carry a key somebody else chose, and then two values say different things
 * about one instance. Nobody noticed that before this check existed, because no inbound
 * contract had a place for a second value at all: every one of them named the workflow
 * with a single string.
 * <p>
 * What happens on a disagreement is a refusal, never a repair. VanillaBP raises no
 * incident of its own on any BPMS: it ends the invocation the way it ends one for a task
 * nobody serves, and each BPMS then applies what it applies to any failing handler.
 * Camunda 7 retries the job and raises an incident afterwards, Camunda 8 counts the job's
 * retries down and raises one afterwards, and behind the Process-Engine-API it is that
 * engine's business. The outcome is the same everywhere: the workflow does not move on an
 * identity VanillaBP cannot vouch for, and the message names both values.
 * <p>
 * See decision 69 in the repository's DECISIONS.md, which also says why an empty business
 * key contradicts nothing and why there is no way to switch this off.
 */
public final class BusinessKeyCheck {

  private BusinessKeyCheck() {
    // utility class
  }

  /**
   * Refuses what the BPMS handed over when its business key names something other than
   * the workflow aggregate.
   * <p>
   * Nothing happens where either value is absent. An empty business key says nothing, so
   * it cannot disagree, and that is the state of every Camunda 8 workflow up to cluster
   * 8.9 and of every workflow on a BPMS which keeps no business key. An absent aggregate
   * id is a different defect, reported where it is read.
   *
   * @param businessKey What the BPMS keeps as the workflow's business key, or
   *          <code>null</code> where it keeps none
   * @param workflowAggregateId The workflow aggregate's id in serialized form
   * @param whatIsRefused What the BPMS handed over, as the start of a sentence, e.g.
   *          "The delivery of task 'assessRisk'"
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param adapterId The adapter which handed it over, or <code>null</code>
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code>
   * @throws IllegalStateException Naming both values and the way out
   */
  public static void refuseAKeyWhichIsNotTheAggregateId(
      final String businessKey,
      final String workflowAggregateId,
      final String whatIsRefused,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String workflowId) {

    if ((businessKey == null) || businessKey.isBlank()) {
      return;
    }
    if ((workflowAggregateId == null) || workflowAggregateId.isBlank()) {
      return;
    }
    if (businessKey.equals(workflowAggregateId)) {
      return;
    }
    throw new IllegalStateException(
        ("%s is refused: the workflow names itself twice and the two names disagree. The BPMS keeps "
            + "business key '%s' while the workflow aggregate's id is '%s' (workflow module '%s', BPMN "
            + "process '%s'%s%s). VanillaBP names a workflow by its workflow aggregate and by nothing "
            + "else, so a business key counts only where it carries that id. A workflow VanillaBP "
            + "started carries it, which means this one was started past VanillaBP: either start it "
            + "through ProcessService, or write the workflow aggregate's id into the business key "
            + "wherever it is started. Refusing is all VanillaBP does here - what follows is what this "
            + "BPMS does with any failing task.")
            .formatted(
                whatIsRefused,
                businessKey,
                workflowAggregateId,
                workflowModuleId,
                bpmnProcessId,
                adapterId == null ? "" : ", adapter '%s'".formatted(adapterId),
                workflowId == null ? "" : ", workflow '%s' in the BPMS".formatted(workflowId)));

  }

}
