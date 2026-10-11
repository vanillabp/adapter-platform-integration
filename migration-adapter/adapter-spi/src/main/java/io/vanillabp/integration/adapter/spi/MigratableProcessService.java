package io.vanillabp.integration.adapter.spi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
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
 * {@link WorkflowScope} it is GIVEN. That is a narrower question than what its own
 * instance holds. The scope of an adapter instance is what IT deployed: the workflow
 * modules, their BPMN processes, and whatever the BPMS uses to keep those apart (a
 * tenant, prefixed identifiers, an engine table prefix, a database). Anything else is
 * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} and never {@link WorkflowAwareness#ACTIVE}.
 * The unknown answer is what lets the walk reach the adapter which really holds the
 * workflow.
 * <p>
 * <b>Neither the task ID nor the workflow-aggregate ID is enough evidence.</b> An
 * adapter which treats them as evidence claims workflows of its neighbours:
 * <ul>
 * <li>Two adapter ids may address ONE backend. This setup is supported. It moves a
 * workflow module from one scoping to another, on Camunda 8 for example from tenants
 * to prefixed identifiers on one cluster. There the keys are global. A job key, a
 * user-task key and a process-instance key of the other adapter can be addressed, and
 * they do answer.</li>
 * <li>Two workflow modules of ONE backend may have the same aggregate ID. Aggregate
 * IDs are unique per aggregate type, not across an application. So two modules whose
 * aggregates count from one collide.</li>
 * </ul>
 * <b>A probe must not move the workflow forward.</b> It is a question asked before an
 * operation is routed. It is also asked of adapters which do not hold the subject at
 * all. So a probe which completes a task, correlates a message or writes a variable
 * moves a workflow which belongs to somebody else.
 * <p>
 * A command which does not move the workflow forward IS allowed, if that is the only way
 * to ask. It has to be scoped before it is sent. The Camunda 8 adapter is the example.
 * Its {@code awarenessOfTask} updates the timeout of the job, and its
 * {@code awarenessOfUserTask} sends an empty user-task update. Both renew a lock and
 * move nothing forward. Both ask the scope question first, because a cluster key does
 * not say which adapter id it belongs to (see decision 3 of that adapter's
 * {@code DECISIONS.md}). The visible effect is a renewed lock on a job which the caller
 * is about to complete or cancel.
 * <p>
 * The core cannot check any of this. The core decides which adapters may be asked (the
 * prioritized list). Only the adapter knows which workflows it owns.
 * {@code WorkflowLocator} stops at the first {@link WorkflowAwareness#ACTIVE}, so it can
 * only be as right as the answers it gets. {@code ElectionScopeContractTest} of the
 * migration adapter checks this.
 *
 * <h2>The two-phase contract</h2>
 *
 * Every operation which goes out to the BPMS is split in two. The split is the same for
 * every adapter and every operation: <b>phase one asks, phase two acts</b>. Phase one
 * runs inside the transaction the application called from. It may only ask questions
 * and take locks: does the task still exist, is a subscription waiting for this
 * message? So a wrong call fails where the application made it, with a stack trace
 * which still points at business code. Phase two runs after that transaction has
 * committed. The {@link PhaseTwoOutbox} sends it, and it is the only place where the
 * BPMS is changed.
 * <p>
 * This holds whether the BPMS is remote or runs embedded in the application. An
 * embedded engine could commit together with the application. But it cannot repeat a
 * command which lost a concurrency conflict inside the caller's transaction, because
 * the conflict marks that transaction rollback-only. So there is no switch which lets an
 * adapter act in phase one. An adapter which starts a workflow, completes a task or
 * broadcasts a signal in phase one silently breaks the contract. The core schedules
 * phase two anyway, and the operation happens twice.
 * <p>
 * This does not change the other direction. A BPMS which delivers a task inside its own
 * transaction still does so, and
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#runInCurrentTransaction()}
 * reports it. Incoming work may share the caller's transaction, outgoing work never
 * does.
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
   * Which configured adapter instance serves this process service. The core writes the id
   * into what it stores about a workflow, for example an outbox entry or a delivery record.
   * It reads the id back to reach the same BPMS again. That is why the id is an identity
   * and not a label. See decision 17 in the repository's DECISIONS.md.
   *
   * @return The adapter's ID this service belongs to
   */
  String getAdapterId();

  /**
   * What this adapter does for each operation it can serve, by operation.
   * <p>
   * All outgoing work of an adapter is here: one {@link PhaseOperationHandler} per
   * {@link PhaseOperation}, each of them the pair of "ask" and "act" for this BPMS.
   * Everything else about an operation belongs to the operation and to the core: which
   * BPMS runs it, which probe finds that BPMS, what its idempotency key is, how a failure
   * is worded. So an operation added later costs an adapter exactly one entry here.
   * <p>
   * If an operation is missing from the map, this BPMS has nothing like it. That is
   * allowed for an operation which is not {@link PhaseOperation#requiredOfEveryAdapter()}.
   * A BPMS without signals is simply a BPMS without signals. The application learns this
   * when it asks ({@link PhaseOperationNotSupported}). If a required operation is
   * missing, the start fails instead, and the message names this adapter.
   * <p>
   * Seven of the nine core operations are required of every adapter. They are the
   * shortest description of what a BPMS has to be able to do to carry VanillaBP:
   * {@link PhaseOperation#START_WORKFLOW}, {@link PhaseOperation#START_WORKFLOW_BY_MESSAGE},
   * {@link PhaseOperation#COMPLETE_TASK}, {@link PhaseOperation#CANCEL_TASK},
   * {@link PhaseOperation#COMPLETE_USER_TASK}, {@link PhaseOperation#CANCEL_USER_TASK} and
   * {@link PhaseOperation#CORRELATE_MESSAGE}. The two which may be left out are
   * {@link PhaseOperation#SEND_SIGNAL} and {@link PhaseOperation#AGGREGATE_CHANGED}.
   * {@link PhaseOperation#CORE_OPERATIONS} is the list at runtime, and
   * {@link PhaseOperation#requiredOfEveryAdapter()} the flag. So an operation added later
   * answers for itself, and this paragraph does not get out of date.
   * <p>
   * A third way is to register a required operation with a handler which only throws. It
   * is the better way if the BPMS has the concept but this adapter cannot reach it. Then
   * the message comes from the adapter and says which API is missing. Otherwise the core
   * only says that the operation is not supported.
   * <p>
   * Some adapters serve an operation only under some condition, for example on a BPMS
   * whose signal API is optional. Such an adapter leaves the operation out if that
   * condition does not hold. The map is built while the application starts, and it may be
   * built fresh here.
   *
   * @return A handler per operation this adapter serves
   */
  Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations();

  /**
   * Finds out whether the target BPMS knows the given task. The migration adapter uses it
   * to elect the BPMS which is responsible for an existing workflow. It asks the adapters
   * in the order of the configured prioritized adapters.
   * <p>
   * <b>Contract:</b> {@link WorkflowAwareness#BPMS_UNAVAILABLE} means &quot;do not
   * fall back to the next adapter, retry later&quot;. Only
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} allows falling back to the next adapter of
   * the prioritized list.
   * <p>
   * The ID of the workflow aggregate is passed in addition to the ID of the task, because
   * a task ID alone says nothing. It is unique within the BPMS which issued it, and means
   * something else in the next one. The two together narrow the subject down. But
   * NEITHER of them proves that the task belongs to the scope of this adapter. The
   * election contract in the type javadoc decides the answer.
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
   * Finds out whether the target BPMS knows the workflow of the given workflow aggregate.
   * This method about the instance exists (in addition to
   * {@link #awarenessOfTask(WorkflowScope, Object, String)}), because message correlation has no
   * task ID to ask for.
   * <p>
   * <b>Contract:</b> {@link WorkflowAwareness#BPMS_UNAVAILABLE} means &quot;do not
   * fall back to the next adapter, retry later&quot;. Only
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} allows falling back to the next adapter of
   * the prioritized list. For workflows, {@link WorkflowAwareness#ACTIVE} means &quot;the
   * workflow is active&quot;, and {@link WorkflowAwareness#COMPLETED} means &quot;the
   * workflow has ended&quot;.
   * <p>
   * The aggregate persistence is passed for an adapter of a BPMS without a business key.
   * Such an adapter finds the workflow by the process variable which carries the ID of the
   * aggregate. That variable is named after the ID attribute of the aggregate
   * ({@link AggregatePersistenceAware#getAggregateIdName()}). So the name has to be known
   * at PROBE time. The election runs before any other SPI method of an operation, so an
   * adapter must never take the name from a previous call.
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
   * The same question, with the BPMS' own id of the workflow, if VanillaBP holds one. The
   * election asks this one wherever the question is about a workflow: for an operation on
   * it, in both phases, and on the paths which wait for an eventually consistent BPMS.
   *
   * <h4>Why the id is worth having</h4>
   *
   * Measured on three Camunda 8 clusters in September 2026: the engine knows a new instance
   * 16 to 19 ms after the create was sent. The search which
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} uses
   * finds it after 167 to 1324 ms. After a cancellation the engine says "gone" after 21 ms,
   * and the search needs 176 to 2068 ms to agree. So an engine answers much earlier than
   * its index, and every command which asks it addresses a KEY. VanillaBP keeps that key
   * next to the workflow it belongs to: in the row written when the workflow started, in
   * the record of a delivery and in the election cache. It hands the key over here.
   *
   * <h4>What you may do with it, and what you may not</h4>
   *
   * You may SHORTEN A YES. Ask your engine by the key. If it says the workflow runs, answer
   * {@link WorkflowAwareness#ACTIVE} without waiting for your read model.
   * <p>
   * You may NOT turn a negative answer into {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. An
   * engine forgets a workflow the moment it ends. So "the engine does not know the key"
   * cannot tell {@link WorkflowAwareness#COMPLETED} from
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. Only the second one lets the election move
   * on to the next BPMS. In a migration setup, an ended workflow read as unknown sends the
   * next operation to the wrong BPMS. If the key answers nothing, fall back to the answer
   * you would have given without it.
   * <p>
   * The id is a HINT and may be wrong. It says what was true when VanillaBP learned it, not
   * what is true now. <code>null</code> means nobody knew one. An adapter which cannot use
   * the id does not implement this method at all. The default drops the id and answers
   * exactly as
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} does. So
   * every adapter written before this method keeps compiling and behaving as before.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param aggregatePersistence The workflow aggregate's persistence support
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> if
   *          VanillaBP holds none
   * @return The BPMS' awareness of the workflow within that scope (see the election
   *         contract in the type javadoc)
   */
  default WorkflowAwareness awarenessOfWorkflow(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String workflowId) {

    return awarenessOfWorkflow(scope, aggregatePersistence, workflowAggregateId);

  }

  /**
   * Finds out whether the target BPMS knows the workflow of the given workflow aggregate.
   * It is asked ONLY before a START outbox entry of the two phases is sent again, after a
   * recovery or a retry. This protects the at-least-once delivery: if the workflow is
   * already known, the start already succeeded. Then the entry is used up without
   * starting a second instance.
   * <p>
   * <b>Contract, stricter than
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)}:</b> the
   * answer must NEVER be optimistic. Answering {@link WorkflowAwareness#ACTIVE} or
   * {@link WorkflowAwareness#COMPLETED} SKIPS the start. So a wrong &quot;known&quot;
   * LOSES a workflow. A wrong &quot;unknown&quot; only produces the duplicate which
   * at-least-once delivery allows anyway. Some adapters cannot query the BPMS reliably,
   * for example Camunda 8 without secondary storage. There
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} answers
   * an optimistic ACTIVE for the election on purpose. Such an adapter must override this
   * method and return {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. Then the start goes ahead,
   * and the idempotency contract of {@link PhaseOperationHandler#phaseTwo} applies.
   * <p>
   * The default delegates to
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)}. That is
   * correct for adapters whose workflow awareness is an honest query to the engine.
   * <p>
   * The scope rule of the election contract (type javadoc) applies here as well, for the
   * same reason in the other direction. A workflow of ANOTHER scope is no evidence that
   * THIS adapter already started the one which is being sent again.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param aggregatePersistence The workflow aggregate's persistence support
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The BPMS' awareness of the workflow. If unsure, answer
   *         {@link WorkflowAwareness#UNKNOWN_TO_BPMS}
   */
  default WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    return awarenessOfWorkflow(scope, aggregatePersistence, workflowAggregateId);

  }

  /**
   * The same question as
   * {@link #awarenessOfWorkflowForRedispatch(WorkflowScope, AggregatePersistenceAware, Object)},
   * but only about the workflows started at or after the given moment. The core calls this
   * method.
   * <p>
   * An aggregate may carry a second workflow once its first one has ended. The outbox entry of
   * that second start was planned long after the first workflow started. The first attempt to
   * send the second start may have failed before it created anything. Then the first workflow
   * is no evidence for the second one. An answer which counts it SKIPS the start and loses the
   * second workflow. Counting only the workflows started at or after <code>plannedAt</code>
   * tells the two apart. The BPMS' own start time of the workflow is compared with it
   * (Camunda 7: the start time in the history, Camunda 8: the <code>startDate</code> the
   * search reports).
   * <p>
   * <code>plannedAt</code> comes from the clock of the node which planned the start, and the
   * BPMS reads its own clock. The BPMS' clock may be behind by more than the time between
   * planning and the first sending. Then the workflow this entry started looks older than the
   * entry, and the answer is {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. That costs a duplicate,
   * which the contract allows anyway. Moving the moment back to allow for the clock difference
   * would turn the same error into a lost workflow. So an adapter does not do that.
   * <p>
   * The default ignores the moment and asks
   * {@link #awarenessOfWorkflowForRedispatch(WorkflowScope, AggregatePersistenceAware, Object)}.
   * An adapter written before this method existed answers that way. Such an adapter still
   * skips the second start of an aggregate in the case above.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param aggregatePersistence The workflow aggregate's persistence support
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param plannedAt When the start was planned. <code>null</code> for an entry planned by a
   *          version which did not record it. Then every workflow of the aggregate counts
   * @return The BPMS' awareness of a workflow started at or after <code>plannedAt</code>. If
   *         unsure, answer {@link WorkflowAwareness#UNKNOWN_TO_BPMS}
   */
  default WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final java.time.Instant plannedAt) {

    return awarenessOfWorkflowForRedispatch(scope, aggregatePersistence, workflowAggregateId);

  }

  /**
   * Whether this adapter can answer
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} by
   * ASKING its BPMS, not by assuming.
   * <p>
   * The election walks the prioritized adapters and stops at the first
   * {@link WorkflowAwareness#ACTIVE}. So it is exactly as right as the answers it gets.
   * Some adapters cannot ask: on a Camunda 8 cluster without secondary storage, or on the
   * Process-Engine-API, whose commands carry no query at all. Such an adapter has to answer
   * something. Answering {@link WorkflowAwareness#ACTIVE} optimistically is the only
   * answer which keeps an application with a single BPMS working. But next to a second
   * adapter, the same answer routes operations by the order of the list, not by evidence.
   * Then it takes the workflows of the other BPMS.
   * <p>
   * If this method says <code>false</code>, the core can refuse that combination while
   * the application starts. Otherwise it would only be found in production. The default
   * is <code>true</code>. An adapter which asks its BPMS needs to say nothing.
   * <p>
   * The answer is fetched AFTER this adapter deployed. Some adapters learn what their BPMS
   * can do only while deploying. Camunda 8, for example, learns about the query API from
   * the first query which fails. Such an adapter may answer from what it found out by
   * then.
   *
   * @return Whether the workflow probe of this adapter asks rather than guesses
   */
  default boolean canLocateWorkflows() {

    return true;

  }

  /**
   * How long this BPMS may need until
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)} can find
   * a workflow it holds, and how often to ask in the meantime.
   * <p>
   * The default is {@link WorkflowVisibilityDelay#none()}. A BPMS which answers from the
   * transaction which created the instance never needs a second look. An adapter whose
   * probe reads an eventually consistent model reports a window. The core waits for it
   * where it has a reason to believe that this adapter holds the workflow. It never waits
   * for a workflow nobody ever heard of.
   *
   * @return The delay, never <code>null</code>
   */
  default WorkflowVisibilityDelay workflowVisibilityDelay() {

    return WorkflowVisibilityDelay.none();

  }

  /**
   * The same window, asked for ONE workflow. The core calls this one. So an adapter can be
   * patient where waiting helps, and quick where it does not.
   *
   * <h4>Why one window for everything is too coarse</h4>
   *
   * The window exists for workflows which were just started. Measured on three Camunda 8
   * clusters in September 2026, the engine knows a new instance after 16 to 19 ms. The
   * search {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)}
   * uses finds it after 167 to 1324 ms. For those workflows the probe ends up answering
   * {@link WorkflowAwareness#ACTIVE}. A workflow the engine no longer holds is a different
   * case. It is old enough to be in the read model for a long time already, and after its
   * end that model needs 176 to 2068 ms to agree. If the window is asked once for the
   * whole adapter, it has to be the long one. Then the case which needs a second waits the
   * ten seconds a young workflow may need.
   *
   * <h4>What you may do with it, and what you may not</h4>
   *
   * You may SHORTEN THE WAIT for a workflow you already know something about, for example
   * one your own probe just reported as gone. For everything else, answer today's window.
   * <p>
   * You may NOT let a shorter window change WHAT you answer afterwards. Shortening it only
   * ends the waiting sooner. The answer which comes out of it is still the honest one.
   * Above all, a workflow which ENDED is {@link WorkflowAwareness#COMPLETED} and never
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. Only the unknown answer lets the election
   * move on to the next BPMS. So an ended workflow read as unknown sends the next
   * operation of a migration to the wrong BPMS.
   * <p>
   * The id is a HINT and may be wrong. It says what was true when VanillaBP learned it,
   * not what is true now. <code>null</code> means the caller holds none. That is a normal
   * case, and it asks for the window of an adapter which knows nothing about this
   * workflow. An adapter which cannot use the id does not implement this method at all.
   * The default drops the id and answers exactly what {@link #workflowVisibilityDelay()}
   * answers. So every adapter written before this method keeps compiling and behaving as
   * before.
   *
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> if
   *          VanillaBP holds none
   * @return The delay, never <code>null</code>
   */
  default WorkflowVisibilityDelay workflowVisibilityDelay(
      final String workflowId) {

    return workflowVisibilityDelay();

  }

  /**
   * Finds out whether the target BPMS knows the given USER task. The contract is the same
   * as for {@link #awarenessOfTask(WorkflowScope, Object, String)}. User tasks have their
   * own probe, because their IDs live in another namespace than the IDs of service tasks
   * (e.g. Camunda 7 task ID vs. execution ID, Camunda 8 user-task key vs. job key).
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
   * Whether this BPMS may deliver the same task more than once. Then the core has to
   * remember what it processed (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
   * This is true for every REMOTE BPMS. The task is reported as done after the local
   * transaction was committed. So a crash in between makes the BPMS repeat the delivery.
   * <p>
   * The default is <code>false</code>. That is the answer of an EMBEDDED BPMS which
   * delivers tasks inside the application's transaction
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#runInCurrentTransaction()}).
   * There a repeated delivery means that nothing was committed. So there is nothing to
   * remember, and removing duplicates would have no effect.
   * <p>
   * An adapter which answers <code>true</code> should report a delivery identity with
   * every invocation context
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getDeliveryId()}).
   * Without one, the core cannot tell a repeated delivery from a new task, and it calls
   * the handler for every delivery. The answer is used at STARTUP, too. It decides whether
   * a missing delivery log is worth a guiding message.
   *
   * @return Whether tasks may be delivered more than once
   */
  default boolean deliversTasksAtLeastOnce() {

    return false;

  }

  /**
   * What this BPMS does with one Java type. It is asked once per type while the application
   * starts. The core knows which values travel and in which direction. This answer says
   * what happens to them on the way.
   * <p>
   * It is asked for both directions, because different things decide the two. The
   * expression language of the BPMS reads a value of the workflow aggregate
   * ({@link ValueDirection#TO_BPMS}). Only the handler reads a value which arrives in a
   * <code>&#64;TaskParam</code> parameter, so only the serialization decides
   * ({@link ValueDirection#FROM_BPMS}).
   * <p>
   * How the adapter finds its answer is up to the adapter. It may hold a list of the types
   * its BPMS stores. It may send a value through its own serializer and read it back. But
   * it must not guess. The default answer is {@link ValueTypeVerdict#cannotSay(String)},
   * and a start never ends because of that answer. Camunda 8 drops the scale of a decimal
   * inside the broker, and no adapter sees that without a running cluster. And an
   * application whose BPMS cannot be reached while it starts still has to start.
   *
   * @param valueType The declared Java type of the value
   * @param direction Which way the value travels
   * @return What this BPMS does with the type, never <code>null</code>
   */
  default ValueTypeVerdict whatThisBpmsDoesWith(
      final Class<?> valueType,
      final ValueDirection direction) {

    return ValueTypeVerdict
        .cannotSay(
            "the adapter '%s' says nothing about the types its BPMS stores".formatted(getAdapterId()));

  }

  /**
   * How many tasks of one BPMN process this BPMS holds open right now. It is asked once at
   * startup, never at runtime.
   *
   * <h4>What it is for</h4>
   *
   * VanillaBP answers a repeated delivery from what it wrote down when the handler ran. A
   * task which was already open before this application ever ran has no such record. So
   * its next delivery runs the handler a second time. Version 1 always did that, and the
   * documentation of version 2 promises it will not. This gap is real. It closes on its
   * own, and nobody can see it without this number.
   * <p>
   * Nothing can be taken over here, so only a count is asked for. An activated job which
   * is still there may belong to a handler which waits for its completion, or to a
   * handler which crashed halfway. No BPMS can tell the two apart. A record written for
   * the second case would skip business code which never ran.
   *
   * <h4>Who answers</h4>
   *
   * A BPMS which delivers INSIDE the application's transaction has no delivery identity.
   * So VanillaBP keeps no records for it, and the question makes no sense. Such a BPMS
   * answers <code>null</code>, which is the default. Every other BPMS answers what it can
   * count cheaply. It answers <code>null</code> if counting needs something the
   * installation does not have.
   * <p>
   * "Cheaply" means the BPMS counts and the adapter reads the number. An adapter which
   * fetches the open tasks to count them transfers a page which grows with every year the
   * application runs, and the start takes longer with it. See decision 19 in the
   * repository's DECISIONS.md.
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
   * The outbox repeats a failed sending until the entry is blocked. So an operation which
   * moves the workflow forward survives a lost concurrency conflict. An embedded engine
   * reports such a conflict as its own exception type, and the next attempt just wins.
   * Some failures the BPMS answers the same way every time (a malformed request, an
   * identifier which does not exist). Repeating them gains nothing. If this method says
   * so, the entry is blocked at once. Then operations staff see it while the log still
   * says why.
   * <p>
   * The default is <code>true</code>. Repeating is the safe answer, and every store did
   * it before adapters could classify failures at all.
   *
   * @param failure What the phase-two operation threw
   * @return Whether repeating the operation may succeed
   */
  default boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    return true;

  }

  /**
   * The viewer/history API. It only reads, and it has no phases. The adapter which holds
   * the workflow answers. It is elected by probing
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object)}.
   * <p>
   * Returns the process definitions used by the workflow of the given aggregate. That is
   * the definition the workflow itself runs on (its {@code usedByElements} is
   * <code>null</code>), plus the definitions of the call activities of that definition,
   * in the version which WOULD run next (their {@code usedByElements} name the ids of the
   * call-activity elements which use them). The <code>ProcessService</code> javadoc of
   * spi-for-java has the full meaning.
   * <p>
   * <b>Definition ids are the adapter's own ids here.</b> The core adds a namespace per
   * adapter id before it hands them to the application, and removes it again on the way
   * back. A definition id has to stay findable by {@link #getBpmnXml(String, String, String)}
   * of the right adapter, although that method gets no aggregate to elect by.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param historyContext <code>null</code> for the workflow's primary process or
   *        a value reported as
   *        {@code WorkflowElementHistory#secondaryWorkflowHistoryContext()} by
   *        {@link #getWorkflowHistory} for a call activity already executed
   * @return The process definitions. An EMPTY list means "this adapter does not
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
   * The definition id is the adapter's own part of the id reported by
   * {@link #getProcessDefinitions}. The core removes its adapter namespace before it
   * calls.
   *
   * @param workflowModuleId The ID of the workflow module the definition belongs to
   * @param bpmnProcessId The BPMN process ID of the process service asked
   * @param processDefinitionId The ADAPTER-NATIVE process definition id
   * @return The BPMN XML, or <code>null</code> if this adapter does not know the
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
   * The returned {@code WorkflowHistory#processDefinitionId()} is the adapter's OWN id
   * (the core adds the namespace, see {@link #getProcessDefinitions}). A BPMS which
   * records no element history reports {@code WorkflowHistory#elementsHistory()} as
   * <code>null</code>. That is the SPI's documented answer for "not supported by the
   * BPMS", NOT an error. The same holds for a BPMS whose history is eventually
   * consistent and has not caught up yet. Report what is visible, never an error.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param historyContext <code>null</code> for the workflow's primary process or
   *        the secondary history context of a call activity already executed
   * @return The history. <code>null</code> means "this adapter does not know the
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
