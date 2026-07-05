package io.vanillabp.integration.adapter.spi;

/**
 * The result of asking an adapter whether its BPMS is aware of a certain workflow or task.
 * Used by the migration adapter to elect the BPMS responsible for an existing workflow:
 * adapters are asked in the order of the configured prioritized adapters.
 * <p>
 * The distinction between {@link #UNKNOWN_TO_BPMS} and {@link #BPMS_UNAVAILABLE} is
 * crucial for BPMS providing eventual consistency (e.g. remote BPMS): only a definite
 * &quot;this BPMS does not know the workflow/task&quot; permits falling back to the next
 * adapter of the prioritized list, whereas a temporary failure must not.
 */
public enum WorkflowAwareness {

  /**
   * The BPMS knows the workflow/task and the task is currently active. Operations
   * (e.g. completing the task) are expected to succeed using this adapter.
   */
  TASK_ACTIVE,

  /**
   * The BPMS knows the workflow/task but the task was already completed (or the
   * workflow has ended). Do not fall back to the next adapter - the BPMS asked is
   * (or was) the one responsible, the operation simply comes too late.
   */
  TASK_COMPLETED,

  /**
   * The BPMS definitely does not know the workflow/task. Falling back to the next
   * adapter of the prioritized list is permitted.
   */
  UNKNOWN_TO_BPMS,

  /**
   * The BPMS could not be asked (e.g. temporarily unreachable, request timed out).
   * Do <b>not</b> fall back to the next adapter - the workflow might live in this
   * BPMS. The operation has to be retried later instead.
   */
  BPMS_UNAVAILABLE

}
