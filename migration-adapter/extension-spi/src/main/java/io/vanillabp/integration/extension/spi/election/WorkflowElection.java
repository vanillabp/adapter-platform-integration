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

}
