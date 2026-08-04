package io.vanillabp.integration.adapter.spi;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;

/**
 * To be implemented by a platform integration adapter.
 *
 * @param <A> The aggregate type
 */
public interface MigratableProcessService<A> {

  /**
   * @return The adapter's ID this service belongs to
   */
  String getAdapterId();

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
   * The workflow aggregate's ID is passed additionally to the task's ID because task
   * IDs are not unique across BPMSs - the aggregate ID identifies the workflow
   * instance the task is expected to belong to.
   *
   * @param workflowAggregateId The ID of the workflow aggregate the task belongs to
   * @param taskId The task's ID
   * @return The BPMS' awareness of the task
   */
  WorkflowAwareness awarenessOfTask(
      Object workflowAggregateId,
      String taskId);

  /**
   * Determine whether the target BPMS is aware of the workflow belonging to the
   * given workflow aggregate. This instance-level method exists (in addition to
   * {@link #awarenessOfTask(Object, String)}) because message correlation has no
   * task ID to ask for.
   * <p>
   * <b>Contract:</b> {@link WorkflowAwareness#BPMS_UNAVAILABLE} means &quot;do not
   * fall back to the next adapter - retry later&quot;; only
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} permits falling back to the next
   * adapter of the prioritized list. For workflows {@link WorkflowAwareness#ACTIVE}
   * means &quot;the workflow is active&quot; and {@link WorkflowAwareness#COMPLETED}
   * means &quot;the workflow has ended&quot;.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The BPMS' awareness of the workflow
   */
  WorkflowAwareness awarenessOfWorkflow(
      Object workflowAggregateId);

  /**
   * Determine whether the target BPMS is aware of the workflow belonging to the
   * given workflow aggregate, asked ONLY before re-dispatching a recovered or
   * retried two-phase START outbox entry (the at-least-once mitigation: if the
   * workflow is already known, the start already succeeded and the entry is
   * consumed without starting a second instance).
   * <p>
   * <b>Contract - stricter than {@link #awarenessOfWorkflow(Object)}:</b> the
   * answer must NEVER be optimistic. Answering {@link WorkflowAwareness#ACTIVE}
   * or {@link WorkflowAwareness#COMPLETED} SKIPS the start - a wrong
   * &quot;known&quot; therefore LOSES a workflow, whereas a wrong
   * &quot;unknown&quot; merely produces the duplicate the at-least-once residual
   * permits anyway. An adapter that cannot query the BPMS reliably (e.g. Camunda 8
   * without secondary storage, where {@link #awarenessOfWorkflow(Object)}
   * deliberately answers an optimistic ACTIVE for the election) must override
   * this method and return {@link WorkflowAwareness#UNKNOWN_TO_BPMS} - the start
   * proceeds and the adapter's idempotency contract of
   * {@link #startWorkflowPhaseTwo} applies.
   * <p>
   * The default delegates to {@link #awarenessOfWorkflow(Object)} - correct for
   * adapters whose workflow awareness is an honest engine query.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The BPMS' awareness of the workflow - unsure means
   *         {@link WorkflowAwareness#UNKNOWN_TO_BPMS}
   */
  default WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final Object workflowAggregateId) {

    return awarenessOfWorkflow(workflowAggregateId);

  }

  /**
   * Determine whether the target BPMS is aware of the given USER task. Same
   * contract as {@link #awarenessOfTask(Object, String)} - user tasks have their
   * own probe because their IDs live in a different namespace than service-task
   * IDs (e.g. Camunda 7 task ID vs. execution ID, Camunda 8 user-task key vs. job
   * key).
   *
   * @param workflowAggregateId The ID of the workflow aggregate the task belongs to
   * @param taskId The user task's ID
   * @return The BPMS' awareness of the user task
   */
  WorkflowAwareness awarenessOfUserTask(
      Object workflowAggregateId,
      String taskId);

  /**
   * @return Whether the adapter needs a local transaction for starting a workflow properly.
   */
  boolean needsTwoPhaseCommitForStartingWorkflows();

  /**
   * Start a new workflow. Phase one of the two-phase commit. This phase is executed immediately before the
   * local transaction is committed.
   * <p>
   * Possible implementations:
   * <ol>
   *   <li>For BPM systems with eventual consistency this method may be used to...
   *     <ol>
   *       <li>
   *         ...test for availability of a conflicting workflow instance (same BPMN process ID
   *         and same aggregate ID).
   *       </li>
   *       <li>
   *         ...create a lock for starting this workflow instance if this is supported by the BPMS.
   *       </li>
   *     </ol>
   *   </li>
   *   <li>
   *     For embedded BPM systems using the same local transaction as the application this method may be used to...
   *     <ol>
   *       <li>
   *         ...start the workflow without executing the next activities.
   *       </li>
   *       <li>
   *         ...create a lock for starting this workflow instance if this is supported by the BPMS.
   *       </li>
   *     </ol>
   *   </li>
   * </ol>
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregate The workflow-aggregate
   */
  void startWorkflowPhaseOne(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      A workflowAggregate);


  /**
   * Start a new workflow. Phase two of the two-phase commit. This phase is executed immediately after the
   * local transaction is committed. In case of a system crash this method will be called after restarting
   * the application.
   * <p>
   * <strong>Idempotency contract:</strong> The call is scheduled through a
   * {@link PhaseTwoOutbox} having at-least-once semantics: after a crash the call may
   * be repeated even if a previous attempt already succeeded. Adapters MUST tolerate
   * an already-started workflow for the same combination of workflow module, BPMN
   * process and workflow aggregate ID - this triple
   * (<code>workflowModuleId + bpmnProcessId + workflowAggregateId</code>) is the
   * idempotency key. In this situation the method has to return normally without
   * starting a second workflow instance.
   * <p>
   * Possible implementations:
   * <ol>
   *   <li>For BPM systems with eventual consistency this method may be used to...
   *     <ol>
   *       <li>
   *         ...start the workflow.
   *       </li>
   *       <li>
   *         ...release the lock and start this workflow instance if this is supported by the BPMS.
   *       </li>
   *     </ol>
   *   </li>
   *   <li>
   *     For embedded BPM systems using the same local transaction as the application this method may be used to...
   *     <ol>
   *       <li>
   *         ...do nothing if workflow is already started in phase one.
   *       </li>
   *       <li>
   *         ...release the lock and start this workflow instance if this is supported by the BPMS.
   *       </li>
   *     </ol>
   *   </li>
   * </ol>
   *
   * <p>
   * How the workflow aggregate's ID is stored in the BPMS is the adapter's decision:
   * e.g. Camunda 7 uses its dedicated business key, whereas Camunda 8 stores the
   * aggregate as process variables and therefore uses a variable named after the
   * aggregate's ID property (see
   * {@link AggregatePersistenceAware#getAggregateIdName()}).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow to be started
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   */
  void startWorkflowPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId);

  /**
   * Complete an asynchronous task (a <code>&#64;WorkflowTask</code> method with a
   * <code>&#64;TaskId</code> parameter returned without completing). Phase one of
   * the two-phase commit, executed inside the caller's local transaction AFTER the
   * adapter answered {@link WorkflowAwareness#ACTIVE} for the task.
   * <p>
   * Contract (see the two-phase rules): this phase MUST NOT advance the BPMN
   * process. Embedded BPMS sharing the local transaction complete the task entirely
   * here (a rollback takes the engine state with it); remote BPMS only perform a
   * NON-ADVANCING existence check whose sole purpose is to abort the local
   * transaction early if the task is already gone - ideally registered as a
   * pre-commit hook (transaction synchronization) to minimize the window between
   * check and phase two. The actual completion of a remote BPMS happens in
   * {@link #completeTaskPhaseTwo}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregate The workflow-aggregate
   * @param taskId The ID of the task to complete
   */
  void completeTaskPhaseOne(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      A workflowAggregate,
      String taskId);

  /**
   * Complete an asynchronous task - phase two, dispatched through the outbox after
   * the local transaction was committed. Only called for adapters requiring a
   * two-phase commit; embedded BPMS completed the task in phase one already.
   * <p>
   * <strong>Idempotency contract:</strong> at-least-once semantics - the task may
   * already be gone (completed by a previous dispatch attempt, or the workflow
   * moved on). Adapters MUST treat a missing task as success (log it, return
   * normally); throwing is reserved for infrastructure failures (the outbox
   * retries).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the task to complete
   */
  void completeTaskPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId,
      String taskId);

  /**
   * Cancel an asynchronous task by BPMN error. Phase one - same transactional
   * contract as {@link #completeTaskPhaseOne}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregate The workflow-aggregate
   * @param taskId The ID of the task to cancel
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   */
  void cancelTaskPhaseOne(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      A workflowAggregate,
      String taskId,
      String bpmnErrorCode);

  /**
   * Cancel an asynchronous task by BPMN error - phase two, same idempotency
   * contract as {@link #completeTaskPhaseTwo}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the task to cancel
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   */
  void cancelTaskPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId,
      String taskId,
      String bpmnErrorCode);

  /**
   * Complete a USER task - phase one, same transactional contract as
   * {@link #completeTaskPhaseOne} (embedded: complete entirely here; remote:
   * non-advancing existence check only).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregate The workflow-aggregate
   * @param taskId The ID of the user task to complete
   */
  void completeUserTaskPhaseOne(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      A workflowAggregate,
      String taskId);

  /**
   * Complete a USER task - phase two, same idempotency contract as
   * {@link #completeTaskPhaseTwo} (at-least-once; a gone task is tolerated).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the user task to complete
   */
  void completeUserTaskPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId,
      String taskId);

  /**
   * Cancel a USER task by BPMN error - phase one, same transactional contract as
   * {@link #cancelTaskPhaseOne}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregate The workflow-aggregate
   * @param taskId The ID of the user task to cancel
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   */
  void cancelUserTaskPhaseOne(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      A workflowAggregate,
      String taskId,
      String bpmnErrorCode);

  /**
   * Cancel a USER task by BPMN error - phase two, same idempotency contract as
   * {@link #cancelTaskPhaseTwo}.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param taskId The ID of the user task to cancel
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   */
  void cancelUserTaskPhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId,
      String taskId,
      String bpmnErrorCode);

  /**
   * Correlate a message with the workflow of the given aggregate - phase one,
   * executed inside the caller's transaction AFTER the adapter answered
   * {@link WorkflowAwareness#ACTIVE} for the workflow. Embedded BPMS correlate
   * entirely here (a rollback takes the correlation with it); remote BPMS must
   * not advance anything - at most a non-advancing check. PAYLOAD DOCTRINE: no
   * message content is ever transmitted - the aggregate is the single source of
   * truth; only the message name and the optional correlation id travel.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregate The workflow-aggregate
   * @param messageName The BPMN message name
   * @param correlationId The correlation id or <code>null</code> (adapters use
   *        the aggregate ID as the technical correlation key; the correlation id
   *        additionally disambiguates BETWEEN waiting occurrences of the same
   *        message)
   */
  void correlateMessagePhaseOne(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      A workflowAggregate,
      String messageName,
      String correlationId);

  /**
   * Correlate a message - phase two, dispatched through the outbox after the
   * commit. At-least-once: WITHOUT a correlation id the entry has no idempotency
   * key and a redelivered dispatch may double-correlate (documented); adapters
   * use an engine-side deduplication where one exists (e.g. Camunda 8 message
   * id). A workflow gone by dispatch time is tolerated (logged).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param messageName The BPMN message name
   * @param correlationId The correlation id or <code>null</code>
   */
  void correlateMessagePhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId,
      String messageName,
      String correlationId);

  /**
   * Start a new workflow by a message start event - phase one; start semantics
   * apply ({@link #startWorkflowPhaseOne}: embedded BPMS start entirely here,
   * remote BPMS validate only).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregate The workflow-aggregate
   * @param messageName The BPMN message name of the message start event
   */
  void startWorkflowByMessagePhaseOne(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      A workflowAggregate,
      String messageName);

  /**
   * Start a new workflow by a message start event - phase two; idempotency
   * contract like {@link #startWorkflowPhaseTwo} (at most one workflow per
   * aggregate).
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param messageName The BPMN message name of the message start event
   */
  void startWorkflowByMessagePhaseTwo(
      String workflowModuleId,
      String bpmnProcessId,
      AggregatePersistenceAware<A> aggregatePersistence,
      Object workflowAggregateId,
      String messageName);

}
