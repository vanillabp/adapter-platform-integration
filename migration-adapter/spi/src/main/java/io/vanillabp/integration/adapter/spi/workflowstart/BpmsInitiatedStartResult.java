package io.vanillabp.integration.adapter.spi.workflowstart;

import java.util.Map;

/**
 * What the core built for a workflow the BPMS started on its own, and what the
 * adapter has to write back into the BPMS so the workflow can be addressed later.
 * <p>
 * A BPMS with a business-identifier concept of its own (e.g. Camunda 7's business
 * key) stores {@link #workflowAggregateId()} there and ignores the variables. A
 * BPMS without one (e.g. Camunda 8) writes {@link #variables()}, which already
 * contains the aggregate-ID variable under {@link #workflowAggregateIdName()} plus
 * the aggregate values shared per {@code @SyncWithBPMS}.
 *
 * @param workflowAggregateId The workflow aggregate's ID in serialized form
 * @param workflowAggregateIdName The name of the aggregate's ID attribute, which is
 *          how the ID is named as a process variable
 * @param variables The variables to write into the workflow instance - never
 *          <code>null</code>
 * @param created Whether the aggregate was created by this notification;
 *          <code>false</code> means it existed already, so the notification was a
 *          repetition (at-least-once delivery, a retried listener job) and nothing
 *          was created twice
 */
public record BpmsInitiatedStartResult(
                                       String workflowAggregateId,
                                       String workflowAggregateIdName,
                                       Map<String, Object> variables,
                                       boolean created) {

  public BpmsInitiatedStartResult {
    // a defensive copy which tolerates NULL values: an aggregate attribute shared
    // with the BPMS may well be unset, and the BPMS is told exactly that
    variables = variables == null
        ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(variables));
  }

}
