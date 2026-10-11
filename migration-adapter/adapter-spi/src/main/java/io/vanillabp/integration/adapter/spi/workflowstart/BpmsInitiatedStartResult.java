package io.vanillabp.integration.adapter.spi.workflowstart;

import java.util.Map;

/**
 * What the core built for a workflow which the BPMS started by itself. It also says what
 * the adapter has to write back into the BPMS, so the workflow can be addressed later.
 * <p>
 * A BPMS with its own idea of a business identifier (e.g. the business key of Camunda 7)
 * stores {@link #workflowAggregateId()} there and ignores the variables. A BPMS without
 * one (e.g. Camunda 8) writes {@link #variables()}. They already contain the aggregate-ID
 * variable under {@link #workflowAggregateIdName()}, plus the aggregate values shared by
 * {@code @SyncWithBPMS}.
 *
 * @param workflowAggregateId The workflow aggregate's ID in serialized form
 * @param workflowAggregateIdName The name of the aggregate's ID attribute, which is
 *          how the ID is named as a process variable
 * @param variables The variables to write into the workflow instance. Never
 *          <code>null</code>
 * @param created Whether the aggregate was created by this notification.
 *          <code>false</code> means it existed already. So the notification was a
 *          repetition (at-least-once delivery, a retried listener job), and nothing
 *          was created twice
 */
public record BpmsInitiatedStartResult(
                                       String workflowAggregateId,
                                       String workflowAggregateIdName,
                                       Map<String, Object> variables,
                                       boolean created) {

  /**
   * Copies the variables, so an adapter writes what the core built and nothing else. A
   * <code>null</code> VALUE is kept, not refused. An aggregate attribute shared with the
   * BPMS may be unset, and the BPMS is told exactly that.
   */
  public BpmsInitiatedStartResult {
    variables = variables == null
        ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(variables));
  }

}
