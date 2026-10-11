package io.vanillabp.integration.adapter.spi;

/**
 * The result of asking an adapter whether its BPMS is aware of a certain workflow or task.
 * Used by the migration adapter to elect the BPMS responsible for an existing workflow:
 * adapters are asked in the order of the configured prioritized adapters.
 * <p>
 * The difference between {@link #UNKNOWN_TO_BPMS} and {@link #BPMS_UNAVAILABLE} matters a
 * lot for a BPMS which is only eventually consistent (e.g. a remote BPMS). Only a definite
 * &quot;this BPMS does not know the workflow/task&quot; allows falling back to the next
 * adapter of the prioritized list. A temporary failure does not.
 */
public enum WorkflowAwareness {

  /**
   * The BPMS knows the workflow/task and it is currently active. Operations
   * (e.g. completing a task) are expected to succeed using this adapter.
   */
  ACTIVE,

  /**
   * The BPMS knows the workflow/task but it was already completed (or the
   * workflow has ended). Do not fall back to the next adapter. The BPMS asked is (or
   * was) the one responsible, and the operation just comes too late.
   */
  COMPLETED,

  /**
   * The BPMS definitely does not know the workflow/task. Falling back to the next
   * adapter of the prioritized list is permitted.
   */
  UNKNOWN_TO_BPMS,

  /**
   * The BPMS could not be asked (e.g. temporarily unreachable, request timed out).
   * Do <b>not</b> fall back to the next adapter, because the workflow might live in
   * this BPMS. Retry the operation later instead.
   */
  BPMS_UNAVAILABLE

}
