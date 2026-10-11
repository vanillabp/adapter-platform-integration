package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * Asks the BPMS whether one task is still there. Some BPMS report no cancellations. An
 * adapter answers this one question, so the core can work them out by itself.
 * <p>
 * The core calls it on the thread of a delivery. It calls it once for every other task it
 * thinks is open in the same workflow, up to a configured maximum per wake-up
 * (<code>vanillabp.delivery.max-open-tasks-checked</code>). So the question has to be one
 * round trip, never a search. It names the task, and every BPMS which can be asked about
 * a task at all can be asked by its id.
 * <p>
 * The core asks {@link #stillExists(String, String, String)}, which also names the task
 * definition of the record. A probe which implements only
 * {@link #stillExists(String, String)} is asked through the default method and answers as
 * it always did.
 * <p>
 * An adapter which supplies no probe changes nothing and keeps behaving exactly as today.
 * That is why
 * {@link WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas(String, String, TaskInvocationContext, OpenTaskProbe)}
 * only adds something for every adapter written against the current SPI, and breaks nothing.
 * <p>
 * This is NOT
 * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService#awarenessOfTask}.
 * That one answers the question of the election: which of the configured BPMS holds this
 * task. It answers "not mine" as <code>UNKNOWN_TO_BPMS</code>. Read as "gone", that would
 * cancel the open work of a workflow whenever the wrong adapter is asked. This probe asks the
 * adapter which delivered the task, and it only reports whether the task is still there.
 */
@FunctionalInterface
public interface OpenTaskProbe {

  /**
   * Whether the BPMS still has this task.
   *
   * @param workflowId The BPMS' own id of the workflow the task belongs to, as the record
   *          of its delivery kept it
   * @param taskId The BPMS' identity of the task
   * @return What the BPMS said. {@link TaskExistence#CANNOT_SAY} if its answer cannot be
   *         told apart from an outage. Never <code>null</code>
   */
  TaskExistence stillExists(
      String workflowId,
      String taskId);

  /**
   * The same question, which also names the task DEFINITION the record belongs to. The core
   * calls this one, so a probe can tell one kind of task from another before it asks its BPMS.
   *
   * <h4>Why the definition is worth having</h4>
   *
   * The two ids alone do not say what is asked about. On Camunda 8, a user task which the
   * engine manages has a namespace of its own. If its key is passed to a job command, the
   * answer is "not found", although the task is alive. An adapter which cannot tell the two
   * apart has to answer {@link TaskExistence#CANNOT_SAY} for every record of a BPMN process
   * with one such user task. Then the plain service tasks of that process lose their derived
   * cancellation too. The adapter already knows its own elements by their task definition.
   * With it, the refusal narrows from the whole process to the one record.
   *
   * <h4>Why the kind of task does not replace it</h4>
   *
   * Since {@link TaskInvocationContext#getTaskKind()} exists, the delivery record states the
   * kind directly. So somebody asked whether this argument could become a {@link TaskKind},
   * and the string could go. It cannot. The reason is a third answer which the kind cannot
   * carry. Some user tasks carry an <code>updating</code> listener which no method of the
   * application serves. Such a task must not be probed at all. The empty update which asks
   * whether the task is open fires that listener, and nobody answers it. The task then stays
   * in <code>UPDATING</code>, and assign and complete are refused. Which user tasks those are
   * is a property of single ELEMENTS of the model. An adapter which knows only
   * <code>USER_TASK</code> would have to refuse every user task of a process with one such
   * element. The refusal would widen again from the record to the process, and this argument
   * was added to prevent exactly that. The kind would also not help for the records of a BPMN
   * process which the application declares without deploying a model. Nothing about those is
   * known per task at all.
   * <p>
   * So the definition stays, and the kind is not passed in addition. An adapter which wants
   * the kind reads it from its own models. The element property above is there anyway. And
   * the core keeps one argument instead of two which say almost the same thing.
   *
   * <h4>What you may do with it, and what you may not</h4>
   *
   * You may answer {@link TaskExistence#CANNOT_SAY} for the definitions your BPMS cannot
   * be asked about, and ask for the rest. That is the whole point of the argument.
   * <p>
   * You may NOT read more into a negative answer than it says. {@link TaskExistence#GONE} is
   * the only answer which cancels the application's task. An API which cannot tell a refusal
   * from an outage answers {@link TaskExistence#CANNOT_SAY}. The same rule holds in
   * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService#awarenessOfWorkflow(io.vanillabp.integration.adapter.spi.WorkflowScope, io.vanillabp.integration.spi.AggregatePersistenceAware, Object, String)}:
   * a workflow which ended must never be answered as
   * {@link io.vanillabp.integration.adapter.spi.WorkflowAwareness#UNKNOWN_TO_BPMS},
   * because that answer sends the next operation of a migration to the wrong BPMS.
   * <p>
   * A probe which does not implement this method is asked exactly as it always was. The
   * default method drops the definition and calls
   * {@link #stillExists(String, String)}. So every probe written before this still compiles
   * and still decides the same way.
   *
   * @param workflowId The BPMS' own id of the workflow the task belongs to, as the record
   *          of its delivery kept it
   * @param taskId The BPMS' identity of the task
   * @param taskDefinition The task definition of the record, that is the element the adapter
   *          reported the delivery under. <code>null</code> if the record kept none
   * @return What the BPMS said. {@link TaskExistence#CANNOT_SAY} if its answer cannot be
   *         told apart from an outage. Never <code>null</code>
   */
  default TaskExistence stillExists(
      final String workflowId,
      final String taskId,
      final String taskDefinition) {

    return stillExists(workflowId, taskId);

  }

}
