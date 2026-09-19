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

}
