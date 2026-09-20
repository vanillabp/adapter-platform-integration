package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * Asks the BPMS whether one task is still there - the one question an adapter answers so
 * the core can work cancellations out for itself on a BPMS which reports none.
 * <p>
 * The core calls it on the thread of a delivery, once per other task it believes is open in
 * the same workflow, and up to a configured maximum per wake-up
 * (<code>vanillabp.delivery.max-open-tasks-checked</code>). So the question has to be one
 * round trip and never a search: it names the task, and every BPMS which can be asked about
 * a task at all can be asked by its id.
 * <p>
 * The core asks {@link #stillExists(String, String, String)}, which additionally names the
 * task definition of the record. A probe which implements only
 * {@link #stillExists(String, String)} is asked through the default and answers as it
 * always did.
 * <p>
 * An adapter which supplies no probe changes nothing and keeps behaving exactly as today.
 * That is what makes
 * {@link WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas(String, String, TaskInvocationContext, OpenTaskProbe)}
 * additive for every adapter written against the current SPI.
 * <p>
 * This is NOT
 * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService#awarenessOfTask}.
 * That one answers the election's question - which of the configured BPMS holds this task -
 * and folds "not mine" into <code>UNKNOWN_TO_BPMS</code>, which read as "gone" would cancel
 * the open work of a workflow whenever the wrong adapter is asked. This one is asked of the
 * adapter which delivered the task, and the only thing it reports is whether the task is
 * still there.
 */
@FunctionalInterface
public interface OpenTaskProbe {

  /**
   * Whether the BPMS still has this task.
   *
   * @param workflowId The BPMS' own id of the workflow the task belongs to, as the record
   *          of its delivery kept it
   * @param taskId The BPMS' identity of the task
   * @return What the BPMS said, and {@link TaskExistence#CANNOT_SAY} where it said nothing
   *         which can be told apart from an outage. Never <code>null</code>
   */
  TaskExistence stillExists(
      String workflowId,
      String taskId);

  /**
   * The same question, naming the task DEFINITION the record belongs to - what the core
   * calls, so a probe can tell one kind of task from another before it asks its BPMS.
   *
   * <h4>Why the definition is worth having</h4>
   *
   * The two ids alone do not say what is being asked about. On Camunda 8 a user task the
   * engine manages lives in a namespace of its own, and its key handed to a job command
   * answers "not found" for a task which is perfectly alive - so an adapter which cannot
   * tell the two apart has to answer {@link TaskExistence#CANNOT_SAY} for every record of
   * a BPMN process which holds one such user task, and the plain service tasks of that
   * same process lose their derived cancellation with it. The task definition is what the
   * adapter already knows its own elements by, so with it the refusal narrows from the
   * process to the record.
   *
   * <h4>What you may do with it, and what you may not</h4>
   *
   * You may answer {@link TaskExistence#CANNOT_SAY} for the definitions your BPMS cannot
   * be asked about, and ask for the rest. That is the whole point of the argument.
   * <p>
   * You may NOT read a negative answer as more than it is. {@link TaskExistence#GONE} is
   * the only answer which cancels the application's task, and an api which cannot tell a
   * refusal from an outage answers {@link TaskExistence#CANNOT_SAY}. The same rule holds
   * next door in
   * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService#awarenessOfWorkflow(io.vanillabp.integration.adapter.spi.WorkflowScope, io.vanillabp.integration.spi.AggregatePersistenceAware, Object, String)}:
   * a workflow which ended must never be answered as
   * {@link io.vanillabp.integration.adapter.spi.WorkflowAwareness#UNKNOWN_TO_BPMS},
   * because that is the answer which sends the next operation of a migration to the wrong
   * BPMS.
   * <p>
   * A probe which does not implement this method is asked exactly as it always was: the
   * default drops the definition and calls
   * {@link #stillExists(String, String)}, which is what keeps every probe written before
   * this compiling and deciding.
   *
   * @param workflowId The BPMS' own id of the workflow the task belongs to, as the record
   *          of its delivery kept it
   * @param taskId The BPMS' identity of the task
   * @param taskDefinition The task definition of the record - the element the adapter
   *          reported the delivery under, or <code>null</code> where the record kept none
   * @return What the BPMS said, and {@link TaskExistence#CANNOT_SAY} where it said nothing
   *         which can be told apart from an outage. Never <code>null</code>
   */
  default TaskExistence stillExists(
      final String workflowId,
      final String taskId,
      final String taskDefinition) {

    return stillExists(workflowId, taskId);

  }

}
