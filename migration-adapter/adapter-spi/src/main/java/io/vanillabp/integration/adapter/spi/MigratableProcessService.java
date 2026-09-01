package io.vanillabp.integration.adapter.spi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowHistory;

/**
 * To be implemented by a platform integration adapter.
 *
 * <h2>The election contract</h2>
 *
 * An adapter answers the four awareness probes ({@link #awarenessOfTask},
 * {@link #awarenessOfUserTask}, {@link #awarenessOfWorkflow} and
 * {@link #awarenessOfWorkflowForRedispatch}) ONLY for the workflows and tasks of the
 * {@link WorkflowScope} it is GIVEN, which is a narrower question than what its own
 * instance holds. The scope of an adapter instance is what IT deployed: the workflow
 * modules, their BPMN processes, and whatever the BPMS uses to keep those apart - a
 * tenant, prefixed identifiers, an engine table prefix, a database. Anything else is
 * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} and never
 * {@link WorkflowAwareness#ACTIVE}, because the unknown answer is what lets the walk
 * reach the adapter which really holds the workflow.
 * <p>
 * <b>Neither the task ID nor the workflow-aggregate ID is sufficient evidence</b>, and
 * an adapter which treats them as such claims workflows of its neighbours:
 * <ul>
 * <li>two adapter ids may address ONE backend. That is the supported setup which
 * migrates a workflow module from one scoping to another - on Camunda 8 from tenants
 * to prefixed identifiers, on one cluster - and there the keys are global: a job key,
 * a user-task key and a process-instance key of the other adapter are addressable and
 * do answer;</li>
 * <li>two workflow modules of ONE backend may carry the same aggregate ID. Aggregate
 * IDs are unique per aggregate type, not across an application, so two modules whose
 * aggregates count from one collide.</li>
 * </ul>
 * <b>A probe must not ADVANCE the workflow.</b> It is a question asked before an
 * operation is routed, and it is asked of adapters which do not hold the subject at
 * all, so a probe which completes a task, correlates a message or writes a variable
 * moves a workflow belonging to somebody else.
 * <p>
 * A non-advancing command IS allowed where that is the only way to ask, and it has to
 * be scoped before it is sent. The Camunda 8 adapter is the example: its
 * {@code awarenessOfTask} updates the job's timeout and its
 * {@code awarenessOfUserTask} sends an empty user-task update, both of which renew a
 * lock and advance nothing, and both ask the scope question first because a cluster
 * key does not say which adapter id it belongs to (see decision 3 of that adapter's
 * {@code DECISIONS.md}). The visible effect is a renewed lock on a job the caller is
 * about to complete or cancel.
 * <p>
 * The core cannot check any of this: which adapters may be asked is its business (the
 * prioritized list), which workflows an adapter owns is only the adapter's.
 * {@code WorkflowLocator} stops at the first {@link WorkflowAwareness#ACTIVE} and can
 * only be as right as the answers it gets, which is what
 * {@code ElectionScopeContractTest} of the migration adapter holds.
 *
 * <h2>The two-phase contract</h2>
 *
 * Every operation which leaves for the BPMS is split in two, and the split is the same
 * for every adapter and every operation: <b>phase one asks, phase two acts</b>. Phase
 * one runs inside the transaction the application called from and may only ask
 * questions and take locks - does the task still exist, is a subscription waiting for
 * this message - so a wrong call fails where the application made it, with a stack
 * trace still pointing at business code. Phase two runs after that transaction
 * committed, dispatched through the {@link PhaseTwoOutbox}, and is the only place
 * where the BPMS is changed.
 * <p>
 * This holds whether the BPMS is remote or runs embedded in the application: an
 * embedded engine could commit with the application, but it cannot repeat a command
 * which lost a concurrency conflict inside the caller's transaction, because the
 * conflict leaves that transaction rollback-only. There is therefore no switch an
 * adapter could throw to act in phase one, and an adapter which starts a workflow,
 * completes a task or broadcasts a signal in phase one breaks the contract silently:
 * the core will schedule phase two regardless, and the operation happens twice.
 * <p>
 * The other direction is untouched by this. A BPMS which delivers a task inside its
 * own transaction still does so, which is what
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#runInCurrentTransaction()}
 * reports: inbound work may share the caller's transaction, outbound work never does.
 *
 * <p>
 * Two rules of this interface are written down where several places rely on them: an adapter
 * answers the election only for its own scope (decision 4 in the repository's DECISIONS.md), and
 * phase one asks while phase two acts
 * (decision 3 in the repository's DECISIONS.md, which entry 26 completed by removing the switch).
 *
 * @param <A> The aggregate type
 */
public interface MigratableProcessService<A> {

  /**
   * @return The adapter's ID this service belongs to
   */
  String getAdapterId();

  /**
   * What this adapter does for each operation it can serve, by operation.
   * <p>
   * This is where an adapter's outbound work lives: one
   * {@link PhaseOperationHandler} per {@link PhaseOperation}, each of them the pair of
   * "ask" and "act" for this BPMS. Everything else about an operation - which BPMS
   * executes it, which probe finds that BPMS, what its idempotency key is, how a
   * failure is worded - belongs to the operation and to the core, so an operation added
   * later costs an adapter exactly one entry here.
   * <p>
   * An operation which is missing from the map is one this BPMS has nothing like. That
   * is allowed for an operation which is not
   * {@link PhaseOperation#requiredOfEveryAdapter()} - a BPMS without signals is a BPMS
   * without signals - and the application learns so when it asks
   * ({@link PhaseOperationNotSupported}). An operation which IS required and is missing
   * fails the boot instead, naming this adapter.
   * <p>
   * An adapter which serves an operation only under some condition - a BPMS whose
   * signal API is optional, say - leaves it out where that condition does not hold: the
   * map is built while the application boots and may be built fresh here.
   *
   * @return A handler per operation this adapter serves
   */
  Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations();

  /**
   * Determine whether the target BPMS is aware of the given task. Used by the
   * migration adapter to elect the BPMS responsible for an existing workflow by
   * asking the adapters in the order of the configured prioritized adapters.
   * <p>
   * <b>Contract:</b> {@link WorkflowAwareness#BPMS_UNAVAILABLE} means &quot;do not
   * fall back to the next adapter - retry later&quot;; only
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} permits falling back to the next
   * adapter of the prioritized list.
   * <p>
   * The workflow aggregate's ID is passed additionally to the task's ID because a task
   * ID alone says nothing: it is unique within the BPMS which issued it and means
   * something else in the next one. The two together narrow the subject down, and
   * NEITHER of them proves that the task belongs to this adapter's scope - see the
   * election contract in the type javadoc, which is what decides the answer.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param workflowAggregateId The ID of the workflow aggregate the task belongs to
   * @param taskId The task's ID
   * @return The BPMS' awareness of the task within that scope
   */
  WorkflowAwareness awarenessOfTask(
      WorkflowScope scope,
      Object workflowAggregateId,
      String taskId);

  /**
   * Determine whether the target BPMS is aware of the workflow belonging to the
   * given workflow aggregate. This instance-level method exists (in addition to
   * {@link #awarenessOfTask(WorkflowScope, Object, String)}) because message correlation has no
   * task ID to ask for.
   * <p>
   * <b>Contract:</b> {@link WorkflowAwareness#BPMS_UNAVAILABLE} means &quot;do not
   * fall back to the next adapter - retry later&quot;; only
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} permits falling back to the next
   * adapter of the prioritized list. For workflows {@link WorkflowAwareness#ACTIVE}
   * means &quot;the workflow is active&quot; and {@link WorkflowAwareness#COMPLETED}
   * means &quot;the workflow has ended&quot;.
   * <p>
   * The aggregate persistence is passed because an adapter of a BPMS without a
   * business key finds the workflow by the process variable carrying the
   * aggregate's ID, and that variable is named after the aggregate's ID attribute
   * ({@link AggregatePersistenceAware#getAggregateIdName()}). The name therefore
   * has to be known at PROBE time: the election runs before any other SPI method
   * of an operation, so an adapter must never derive it from a previous call.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param aggregatePersistence The workflow aggregate's persistence support
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The BPMS' awareness of the workflow within that scope (see the election
   *         contract in the type javadoc)
   */
  WorkflowAwareness awarenessOfWorkflow(
      WorkflowScope scope,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId);

  /**
   * Determine whether the target BPMS is aware of the workflow belonging to the
   * given workflow aggregate, asked ONLY before re-dispatching a recovered or
   * retried two-phase START outbox entry (the at-least-once mitigation: if the
   * workflow is already known, the start already succeeded and the entry is
   * consumed without starting a second instance).
   * <p>
   * <b>Contract - stricter than
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)}:</b> the
   * answer must NEVER be optimistic. Answering {@link WorkflowAwareness#ACTIVE}
   * or {@link WorkflowAwareness#COMPLETED} SKIPS the start - a wrong
   * &quot;known&quot; therefore LOSES a workflow, whereas a wrong
   * &quot;unknown&quot; merely produces the duplicate the at-least-once residual
   * permits anyway. An adapter that cannot query the BPMS reliably (e.g. Camunda 8
   * without secondary storage, where
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} deliberately
   * answers an optimistic ACTIVE for the election) must override this method and
   * return {@link WorkflowAwareness#UNKNOWN_TO_BPMS} - the start proceeds and the
   * adapter's idempotency contract of {@link PhaseOperationHandler#phaseTwo} applies.
   * <p>
   * The default delegates to
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} - correct for
   * adapters whose workflow awareness is an honest engine query.
   * <p>
   * The scope rule of the election contract (type javadoc) applies here as well, and
   * for the same reason in the other direction: a workflow of ANOTHER scope is no
   * evidence that THIS adapter already started the one being re-dispatched.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param aggregatePersistence The workflow aggregate's persistence support
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The BPMS' awareness of the workflow - unsure means
   *         {@link WorkflowAwareness#UNKNOWN_TO_BPMS}
   */
  default WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    return awarenessOfWorkflow(scope, aggregatePersistence, workflowAggregateId);

  }

  /**
   * Whether this adapter can answer
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} by
   * ASKING its BPMS, rather than by assuming.
   * <p>
   * The election walks the prioritized adapters and stops at the first
   * {@link WorkflowAwareness#ACTIVE}, so it is exactly as right as the answers it gets.
   * An adapter which cannot ask - a Camunda 8 cluster without secondary storage, the
   * Process-Engine-API, whose commands carry no query at all - has to answer something,
   * and answering {@link WorkflowAwareness#ACTIVE} optimistically is the only answer
   * which keeps a single-BPMS application working. Next to a second adapter that very
   * answer routes operations by list order instead of by evidence, and the workflows of
   * the other BPMS are the ones it takes.
   * <p>
   * Saying <code>false</code> here is what lets the core refuse that combination while
   * it boots, instead of leaving it to be found in production. The default is
   * <code>true</code>: an adapter which asks its BPMS needs to say nothing.
   * <p>
   * The answer is fetched AFTER this adapter deployed, so an adapter which only learns
   * what its BPMS can do while deploying (Camunda 8 learns about the query API from the
   * first query which fails) may answer from what it found out by then.
   *
   * @return Whether the workflow probe of this adapter asks rather than guesses
   */
  default boolean canLocateWorkflows() {

    return true;

  }

  /**
   * How long this BPMS may need until a workflow it holds becomes findable by
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)}, and how often
   * to ask meanwhile.
   * <p>
   * The default is {@link WorkflowVisibilityDelay#none()}: a BPMS answering from the
   * transaction which created the instance never needs a second look. An adapter
   * whose probe reads an eventually consistent model reports a window, and the core
   * waits it out where it has a reason to believe this adapter holds the workflow -
   * never for a workflow nobody ever heard of.
   *
   * @return The delay - never <code>null</code>
   */
  default WorkflowVisibilityDelay workflowVisibilityDelay() {

    return WorkflowVisibilityDelay.none();

  }

  /**
   * Determine whether the target BPMS is aware of the given USER task. Same
   * contract as {@link #awarenessOfTask(WorkflowScope, Object, String)} - user tasks have their
   * own probe because their IDs live in a different namespace than service-task
   * IDs (e.g. Camunda 7 task ID vs. execution ID, Camunda 8 user-task key vs. job
   * key).
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param workflowAggregateId The ID of the workflow aggregate the task belongs to
   * @param taskId The user task's ID
   * @return The BPMS' awareness of the user task within that scope (see the election
   *         contract in the type javadoc)
   */
  WorkflowAwareness awarenessOfUserTask(
      WorkflowScope scope,
      Object workflowAggregateId,
      String taskId);

  /**
   * Whether this BPMS may deliver the same task more than once, so the core has to
   * remember what it processed (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). True for every REMOTE
   * BPMS: the task is reported as done after the local transaction was committed, so
   * a crash in between makes the BPMS repeat the delivery.
   * <p>
   * The default is <code>false</code> - the answer of an EMBEDDED BPMS delivering
   * tasks inside the application's transaction
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#runInCurrentTransaction()}):
   * a repeated delivery there means that nothing was committed, so there is nothing
   * to remember and deduplication would have no effect.
   * <p>
   * An adapter answering <code>true</code> should report a delivery identity with
   * every invocation context
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getDeliveryId()}) -
   * without one the core cannot tell a redelivery from a new task and keeps invoking
   * the handler for every delivery. The answer is used at STARTUP, too: it decides
   * whether a missing delivery log is worth a guiding message.
   *
   * @return Whether tasks may be delivered more than once
   */
  default boolean deliversTasksAtLeastOnce() {

    return false;

  }

  /**
   * How many tasks of one BPMN process this BPMS is holding open right now - asked once
   * at startup and never at runtime.
   *
   * <h2>What it is for</h2>
   *
   * VanillaBP answers a repeated delivery from what it wrote down when the handler ran.
   * A task which was already open before this application ever ran has no such record,
   * so its next delivery runs the handler a second time - which is what version 1 always
   * did, and what the documentation of version 2 promises it will not. The window is
   * real, it closes on its own, and nobody can see it without this number.
   * <p>
   * Nothing can be adopted here, which is why only a count is asked for: an activated job
   * which is still there may be a handler waiting for its completion or a handler which
   * crashed halfway, no BPMS can tell the two apart, and a record written for the second
   * case would skip business code which never ran.
   *
   * <h2>Who answers</h2>
   *
   * A BPMS delivering INSIDE the application's transaction has no delivery identity, so
   * VanillaBP keeps no records for it and the question is meaningless - it answers
   * <code>null</code>, which is the default. Everybody else answers what they can count
   * cheaply, and <code>null</code> where counting needs something the installation does
   * not have.
   * <p>
   * "Cheaply" means the BPMS counts and the adapter reads the number. An adapter which
   * fetches the open tasks to count them transfers a page which grows with every year the
   * application runs, and the boot grows with it - decision 19 in the repository's
   * DECISIONS.md.
   *
   * @param workflowModuleId The workflow module to ask about
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The number of open tasks, or <code>null</code> if this BPMS cannot say
   */
  default Long openTaskCount(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return null;

  }

  /**
   * Whether a phase-two operation which failed with the given exception is worth
   * repeating.
   * <p>
   * The outbox repeats a failed dispatch until the entry is blocked. That is what
   * makes a progressing operation survivable which lost a concurrency conflict - an
   * embedded engine reports those as its own exception type, and the next attempt
   * simply wins. A failure the BPMS will answer the same way every time (a malformed
   * request, an identifier which does not exist) gains nothing from being repeated:
   * saying so here blocks the entry immediately, so operations see it while the log
   * still says why.
   * <p>
   * The default is <code>true</code>: repeating is the safe answer, and it is what
   * every store did before adapters could classify at all.
   *
   * @param failure What the phase-two operation threw
   * @return Whether repeating the operation may succeed
   */
  default boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    return true;

  }

  /**
   * The viewer/history API - read-only, no phases: the adapter holding the
   * workflow (elected by probing {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)}) answers.
   * <p>
   * Returns the process definitions used by the workflow of the given aggregate:
   * the definition the workflow itself runs on (its {@code usedByElements} is
   * <code>null</code>) plus the definitions of the call activities of that
   * definition in the version which WOULD BE executed next (their
   * {@code usedByElements} name the call-activity element ids using them). See the
   * <code>ProcessService</code> javadoc of the spi-for-java for the full semantics.
   * <p>
   * <b>Definition ids are adapter-native here.</b> The core namespaces them per
   * adapter id before handing them to the application (a definition id has to stay
   * resolvable by {@link #getBpmnXml(String, String, String)} of the right adapter
   * even though that method gets no aggregate to elect by) and un-namespaces them
   * again on the way back.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param historyContext <code>null</code> for the workflow's primary process or
   *        a value reported as
   *        {@code WorkflowElementHistory#secondaryWorkflowHistoryContext()} by
   *        {@link #getWorkflowHistory} for a call activity already executed
   * @return The process definitions - an EMPTY list means "this adapter does not
   *         know the workflow" (the core turns that into the SPI's
   *         {@code WorkflowNotFoundException})
   */
  default List<ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    throw viewerApiNotImplemented("getProcessDefinitions");

  }

  /**
   * Returns the BPMN XML of a process definition of THIS adapter.
   * <p>
   * The definition id is the adapter-native part of the id reported by
   * {@link #getProcessDefinitions} (the core strips its adapter namespace before
   * calling).
   *
   * @param workflowModuleId The ID of the workflow module the definition belongs to
   * @param bpmnProcessId The BPMN process ID of the process service asked
   * @param processDefinitionId The ADAPTER-NATIVE process definition id
   * @return The BPMN XML - <code>null</code> if this adapter does not know the
   *         definition (the core turns that into the SPI's
   *         {@code ProcessDefinitionNotFoundException})
   */
  default InputStream getBpmnXml(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId) {

    throw viewerApiNotImplemented("getBpmnXml");

  }

  /**
   * Returns the execution history of the workflow of the given aggregate.
   * <p>
   * The returned {@code WorkflowHistory#processDefinitionId()} is
   * ADAPTER-NATIVE (namespaced by the core, see {@link #getProcessDefinitions}).
   * A BPMS not recording an element history reports
   * {@code WorkflowHistory#elementsHistory()} as <code>null</code> - which is the
   * SPI's documented answer for "not supported by the underlying BPMS", NOT an
   * error. The same holds for a BPMS whose history is eventually consistent and
   * has not caught up yet: report what is visible, never an error.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param historyContext <code>null</code> for the workflow's primary process or
   *        the secondary history context of a call activity already executed
   * @return The history - <code>null</code> means "this adapter does not know the
   *         workflow" (the core turns that into the SPI's
   *         {@code WorkflowNotFoundException})
   */
  default WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    throw viewerApiNotImplemented("getWorkflowHistory");

  }

  private UnsupportedOperationException viewerApiNotImplemented(
      final String operation) {

    return new UnsupportedOperationException(
        ("The VanillaBP adapter '%s' does not implement '%s' of the viewer/history API! Either the "
            + "adapter predates that API or its BPMS cannot serve it - use an adapter which does, "
            + "or do not call the viewer/history methods of ProcessService for workflows running "
            + "in this BPMS.")
            .formatted(getAdapterId(), operation));

  }

}
