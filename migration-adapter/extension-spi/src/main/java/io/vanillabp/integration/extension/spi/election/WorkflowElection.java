package io.vanillabp.integration.extension.spi.election;

/**
 * Which BPMS holds a workflow right now - the question an extension has to ask before it
 * talks to a BPMS about a running workflow. During a migration the answer changes per
 * workflow, which is why an extension must not address the first-priority adapter and be
 * done with it.
 * <p>
 * The answer comes from the same election every operation of VanillaBP uses: the cached
 * hint first, then the prioritized adapters in order, and never a fallback where a BPMS
 * is unreachable. Unlike an operation advancing a workflow, a COMPLETED workflow is a
 * regular answer here - an extension may well have something to say about a workflow
 * which ended, the way the viewer API reads its history.
 * <p>
 * <b>What this may cost.</b> A BPMS which answers from a read model its exporter feeds
 * does not report a workflow started moments ago. Where a hint says this adapter holds
 * it, the election waits that adapter's window out rather than answering "unknown",
 * because nobody repeats the question for an extension. On a Camunda 8 cluster that
 * window is ten seconds. An extension which asks this while a transaction of the
 * application is open keeps that transaction open for as long as the wait lasts, and
 * with it the database connection it took. Reporting a change out of a service task is
 * exactly that case.
 * <p>
 * Both platforms provide this as a bean.
 */
public interface WorkflowElection {

  /**
   * Which BPMS holds this workflow right now. There is no "unknown" answer here: whoever
   * asks holds a workflow already, so a workflow no BPMS knows is a mistake somewhere and
   * says so instead of turning into a <code>null</code> nobody checks. What asking may cost
   * is written at the type.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return The id of the adapter holding the workflow - never <code>null</code>
   * @throws IllegalStateException If no BPMS knows the workflow, or if the BPMS which
   *           should hold it is unreachable (both messages name what to do)
   */
  String adapterIdOfWorkflow(
      String workflowModuleId,
      String bpmnProcessId,
      Object workflowAggregateId);

  /**
   * The same election, answering the adapter id AND the BPMS' own id of the workflow.
   *
   * <h4>Why both</h4>
   *
   * They stand in the same place. VanillaBP writes a record for every task delivery which
   * keeps the workflow id next to the adapter id, so the adapter id
   * {@link #adapterIdOfWorkflow} hands back was read out of a row which already knew the
   * workflow id.
   *
   * <h4>What you may do with the workflow id</h4>
   *
   * Write it into your own records, print it in a log line beside ours, and hand it back to
   * VanillaBP later, which is what lets an adapter ask its engine by key instead of waiting
   * for a read model to catch up.
   * <p>
   * What you may not do: address the BPMS with it. The id is the BPMS' own key, its shape
   * belongs to the adapter, and an extension which sends commands to an engine behind the
   * adapter's back is outside everything this platform promises. And you may not read a
   * non-null id as "this workflow is still running" - the id is history, the election is
   * the answer about now.
   * <p>
   * <code>null</code> is a regular answer and not an error: nothing VanillaBP holds knew an
   * id, because no delivery was recorded for that workflow or the record expired.
   * <p>
   * This runs exactly the election {@link #adapterIdOfWorkflow} runs, and costs exactly the
   * same - the workflow id rides along, it does not replace the question. The default
   * delegates and leaves the id empty, which is what an implementation written before this
   * existed answers.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return Where the workflow is - never <code>null</code>
   * @throws IllegalStateException If no BPMS knows the workflow, or if the BPMS which
   *           should hold it is unreachable (both messages name what to do)
   */
  default WorkflowLocation locationOfWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return new WorkflowLocation(
        adapterIdOfWorkflow(workflowModuleId, bpmnProcessId, workflowAggregateId), null);

  }

  /**
   * The BPMS' own id of this workflow, as far as VanillaBP wrote it down - and NO election.
   *
   * <h4>How this differs from the two above</h4>
   *
   * They elect, which means they ask a BPMS and they fail where none knows the workflow. This
   * only reads what VanillaBP persisted: the row written when the workflow started, and the rows
   * of the task deliveries of that workflow. No BPMS is asked, nothing is waited for, and nothing
   * is thrown. An extension which wants the id to put into its own report asks this one; an
   * extension which is about to talk to a BPMS asks one of the two above.
   * <p>
   * The id is the one of the workflow the AGGREGATE is. On a BPMS with call activities that is the
   * super-parent instance, and the instances created underneath are not in this answer: they
   * belong to tasks and travel with those tasks.
   *
   * <h4>What an empty answer means, and what it does not</h4>
   *
   * One answer for several situations, and they are NOT distinguishable: this application never
   * started that workflow, the start happened before this version was deployed, the adapter names
   * no workflow id, the row's retention passed
   * (<code>vanillabp.delivery.workflow-start-retention</code>), or there is no store for that
   * aggregate at all. Empty therefore means "VanillaBP does not know", never "there is no such
   * workflow".
   * <p>
   * A non-empty answer says what was true when the workflow started. It does not say that the
   * workflow still runs, and it must not be sent to a BPMS: the shape of the id belongs to the
   * adapter, and an extension addressing an engine behind the adapter's back is outside
   * everything this platform promises.
   * <p>
   * The default answers nothing, which is what an implementation written before this existed
   * answers.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return The workflow's id in the BPMS, or {@link java.util.Optional#empty()} where VanillaBP
   *         holds none
   */
  default java.util.Optional<String> workflowIdOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return java.util.Optional.empty();

  }

  /**
   * The BPMS' own id of this workflow, the version of the process definition it runs on and the
   * adapter which started it, as far as VanillaBP wrote them down. This is NO election.
   * <p>
   * It reads exactly what {@link #workflowIdOf} reads and answers empty in exactly the same
   * situations. What it adds is the version, and whether an empty version may still come (see
   * {@link WorkflowStart}). An extension which needs the version to pick the right
   * implementation for a workflow asks this one, and it may ask right after the start: the row
   * is written when the workflow begins, which is the window in which a BPMS answering from a
   * read model has nothing to say.
   * <p>
   * The adapter in the answer is read from the same row and is no election either. It is the
   * adapter which started the workflow, and a workflow does not change its BPMS, so that adapter
   * holds the workflow until its end.
   * <p>
   * The default answers the id of {@link #workflowIdOf} without an adapter and without a version,
   * which is all an implementation written before this existed knows.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return What VanillaBP holds about the start, or {@link java.util.Optional#empty()} where it
   *         holds no workflow id
   */
  default java.util.Optional<WorkflowStart> workflowStartOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return workflowIdOf(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .map(workflowId -> new WorkflowStart(null, workflowId, null, false));

  }

}
