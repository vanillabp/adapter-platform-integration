package io.vanillabp.integration.extension.spi.election;

/**
 * Which BPMS holds a workflow right now. An extension has to ask this before it talks to a
 * BPMS about a running workflow. During a migration the answer differs from workflow to
 * workflow. So an extension must not just address the adapter with the first priority.
 * <p>
 * The answer comes from the same election every operation of VanillaBP uses: first the
 * cached hint, then the prioritized adapters in order. There is never a fallback if a BPMS
 * cannot be reached. Unlike for an operation which moves a workflow on, a COMPLETED
 * workflow is a regular answer here. An extension may have something to say about a
 * workflow which ended, the way the viewer API reads its history.
 * <p>
 * <b>What this may cost.</b> Some BPMS answer from a read model which their exporter
 * fills. Such a BPMS does not report a workflow started moments ago. If a hint says this
 * adapter holds the workflow, the election waits for the time window of that adapter. It
 * does not answer "unknown", because nobody asks the question again for an extension. On a
 * Camunda 8 cluster that window is ten seconds. If an extension asks this while a
 * transaction of the application is open, that transaction stays open as long as the wait
 * lasts, and so does its database connection. Reporting a change from a service task is
 * exactly that case. None of this cost arises if the row VanillaBP writes when a workflow
 * starts names a configured adapter: see {@link #adapterIdOfWorkflow}.
 * <p>
 * Both platforms provide this as a bean.
 */
public interface WorkflowElection {

  /**
   * Which BPMS holds this workflow right now. There is no "unknown" answer here. Whoever
   * asks already holds a workflow. So a workflow no BPMS knows is a mistake somewhere, and
   * it is reported as one. It does not turn into a <code>null</code> nobody checks. The
   * javadoc of the type says what asking may cost.
   * <p>
   * The row VanillaBP writes when a workflow starts is read first. It names the adapter which
   * started the workflow, and a workflow does not change its BPMS. So if that adapter is still
   * configured for the workflow, it is the answer. No BPMS is asked, and nothing is waited for.
   * This also means that it does not throw after the workflow ended, as long as that row exists
   * (<code>vanillabp.delivery.workflow-start-retention</code>). Without the row, the election
   * asks the BPMS as the javadoc of the type describes.
   * <p>
   * Call this only where you read, never where you report a change: a report must not wait, so
   * it uses {@link #workflowStartOf} and {@link #openUserTasksOf} (column 4 of decision 27 in the
   * repository's DECISIONS.md).
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return The id of the adapter holding the workflow - never <code>null</code>
   * @throws IllegalStateException If there is no start row naming a configured adapter, and no
   *           BPMS knows the workflow or the BPMS which should hold it is unreachable (both
   *           messages name what to do)
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
   * They are stored in the same place. For every task delivery, VanillaBP writes a record
   * which keeps the workflow id next to the adapter id. So the adapter id which
   * {@link #adapterIdOfWorkflow} returns was read from a row which already knew the
   * workflow id.
   *
   * <h4>What you may do with the workflow id</h4>
   *
   * Write it into your own records, print it in a log line next to ours, and pass it back to
   * VanillaBP later. With it an adapter can ask its engine by key, instead of waiting for a
   * read model to catch up.
   * <p>
   * What you may not do: address the BPMS with it. The id is the BPMS' own key, and its shape
   * belongs to the adapter. An extension which sends commands to an engine behind the back of
   * the adapter is outside everything this platform promises. And a non-null id does not mean
   * "this workflow is still running". The id is history. The election is the answer about now.
   * <p>
   * <code>null</code> is a regular answer, not an error. Nothing VanillaBP holds knew an id,
   * because no delivery was recorded for that workflow, or the record expired.
   * <p>
   * This runs exactly the election {@link #adapterIdOfWorkflow} runs, and costs exactly the
   * same. The workflow id comes along, it does not replace the question. If the row about the
   * start of the workflow answers, both values come from that row, and this does not throw
   * after the workflow ended either. The default delegates and leaves the id empty. That is
   * what an implementation written before this method existed answers.
   * <p>
   * Like {@link #adapterIdOfWorkflow}, this is for reading, never for a report (column 4 of
   * decision 27 in the repository's DECISIONS.md).
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return Where the workflow is - never <code>null</code>
   * @throws IllegalStateException If there is no start row naming a configured adapter, and no
   *           BPMS knows the workflow or the BPMS which should hold it is unreachable (both
   *           messages name what to do)
   */
  default WorkflowLocation locationOfWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return new WorkflowLocation(
        adapterIdOfWorkflow(workflowModuleId, bpmnProcessId, workflowAggregateId), null);

  }

  /**
   * The BPMS' own id of this workflow, as far as VanillaBP wrote it down. This is NO election.
   *
   * <h4>How this differs from the two above</h4>
   *
   * They elect. That means they ask a BPMS, and they fail if no BPMS knows the workflow. This
   * method only reads what VanillaBP persisted: the row written when the workflow started, and the
   * rows of the task deliveries of that workflow. No BPMS is asked, and nothing is waited for. An
   * extension which wants the id for its own report asks this one. An extension which is about to
   * talk to a BPMS asks one of the two above.
   * <p>
   * The id is the one of the workflow the AGGREGATE is. On a BPMS with call activities, that is
   * the top-most parent instance. The instances created below it are not in this answer. They
   * belong to tasks, and they travel with those tasks.
   *
   * <h4>What an empty answer means, and what it does not</h4>
   *
   * There is one answer for several situations, and you CANNOT tell them apart. Maybe this
   * application never started that workflow. Maybe the start happened before this version was
   * deployed. Maybe the adapter names no workflow id, or the retention time of the row is over
   * (<code>vanillabp.delivery.workflow-start-retention</code>), or there is no store for that
   * aggregate at all. So empty means "VanillaBP does not know", never "there is no such
   * workflow".
   * <p>
   * A BPMN process which no <code>&#64;WorkflowService</code> of this application declares is a
   * different case. It is refused, as the two methods above refuse it. VanillaBP writes nothing
   * down for such a process. So asking about it is a mistake of the caller, and an empty answer
   * would hide that mistake.
   * <p>
   * A non-empty answer says what was true when the workflow started. It does not say that the
   * workflow still runs, and it must not be sent to a BPMS. The shape of the id belongs to the
   * adapter. An extension which addresses an engine behind the back of the adapter is outside
   * everything this platform promises.
   * <p>
   * The default answers nothing. That is what an implementation written before this method
   * existed answers.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return The workflow's id in the BPMS, or {@link java.util.Optional#empty()} if VanillaBP
   *         holds none
   * @throws IllegalStateException If no workflow service of this application declares the BPMN
   *           process
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
   * It reads exactly what {@link #workflowIdOf} reads, and it answers empty in exactly the same
   * situations. It adds the version, and whether an empty version may still come (see
   * {@link WorkflowStart}). An extension which needs the version to pick the right
   * implementation for a workflow asks this one. It may ask right after the start, because the
   * row is written when the workflow begins. In that time a BPMS which answers from a read model
   * has nothing to say.
   * <p>
   * The adapter in the answer is read from the same row, so it is no election either. It is the
   * adapter which started the workflow. A workflow does not change its BPMS, so that adapter
   * holds the workflow until its end.
   * <p>
   * The default answers the id of {@link #workflowIdOf}, without an adapter and without a
   * version. That is all an implementation written before this method existed knows.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return What VanillaBP holds about the start, or {@link java.util.Optional#empty()} if it
   *         holds no workflow id
   * @throws IllegalStateException If no workflow service of this application declares the BPMN
   *           process
   */
  default java.util.Optional<WorkflowStart> workflowStartOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return workflowIdOf(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .map(workflowId -> new WorkflowStart(null, workflowId, null, false));

  }

  /**
   * The user tasks of this workflow which VanillaBP delivered and which are still open, as far as
   * VanillaBP wrote them down. This is NO election.
   * <p>
   * Like {@link #workflowIdOf}, it only reads the delivery log. No BPMS is asked, and nothing is
   * waited for. So you may ask it inside a transaction of the application, right after a task was
   * delivered. Tasks of called processes are in the answer too, if this application serves their
   * BPMN processes in the same workflow service.
   * <p>
   * An empty list means "VanillaBP knows of no open user task". It does not mean that the workflow
   * has none. A store written before this method existed answers nothing. So does a store which
   * cannot be read, a BPMN process this application does not serve, and a task delivered before
   * this version was deployed. If an extension needs a task which the list does not have, it has
   * to look for it in another way.
   * <p>
   * The default answers an empty list. That is what an implementation written before this method
   * existed answers.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @return The open user tasks, oldest delivery first, never <code>null</code>
   */
  default java.util.List<OpenUserTask> openUserTasksOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return java.util.List.of();

  }

  /**
   * Whether the given aggregate is the one a <code>&#64;WorkflowStartedByBpms</code> method is
   * building right now, on this thread. This is NO election, and it asks nobody.
   * <p>
   * Such a method runs before the workflow it starts can be found. On Camunda 8, the row which says
   * which BPMS holds the workflow is written only after the method has returned. And the BPMS does
   * not show the workflow yet either. So a report about the aggregate from inside the method waits
   * for a BPMS which has nothing to say. An extension which reports changes of an aggregate asks
   * this first, and skips the report if the answer is <code>true</code>. The start is reported
   * anyway once it is done, and an extension which watches starts sees it then.
   * <p>
   * The answer is <code>true</code> only if such a method runs on this thread and the aggregate is
   * NEW: it has no id yet, or an id its persistence does not know. The method may also return an
   * aggregate which exists already and has other workflows and open tasks. A report about that one
   * is a real report, and the answer is <code>false</code>. It is also <code>false</code> if the
   * persistence cannot load an aggregate by its id, because then nothing tells a new aggregate
   * from an existing one.
   * <p>
   * <code>ProcessService#aggregateChanged</code> applies the same rule and does nothing for such
   * an aggregate.
   * <p>
   * The default answers <code>false</code>. That is what an implementation written before this
   * method existed answers.
   *
   * @param workflowAggregate The aggregate a report is about
   * @return Whether it is the aggregate the running start builds
   */
  default boolean isInsideTheStartOf(
      final Object workflowAggregate) {

    return false;

  }

}
