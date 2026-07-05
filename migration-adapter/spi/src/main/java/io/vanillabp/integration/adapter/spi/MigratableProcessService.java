package io.vanillabp.integration.adapter.spi;

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
   * Determine whether the given task is active in the target BPMS.
   *
   * @param taskId The task's ID
   * @return true = active, false = inactive, null = unknown to BPMS
   */
  Boolean isTaskActive(
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
   * @param workflowAggregate The workflow-aggregate
   */
  void startWorkflowPhaseOne(
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
   * @param workflowAggregateId The ID of the workflow aggregate
   */
  void startWorkflowPhaseTwo(
      Object workflowAggregateId);

}
