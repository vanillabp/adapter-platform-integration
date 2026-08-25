package io.vanillabp.integration.spi;

import java.time.Instant;

/**
 * What VanillaBP remembers about a task delivery it processed: the delivery's
 * identity plus the outcome reported to the BPMS. Written by the core through the
 * {@link TaskDeliveryLog} within the transaction which also persists the workflow
 * aggregate, and read again when the BPMS delivers the same task a second time (see
 * the deduplication contract of {@link TaskDeliveryLog}).
 * <p>
 * Everything besides {@link #deliveryKey()} and {@link #outcome()} is context: it
 * makes a record readable for whoever looks into the store while investigating a
 * workflow, and it is what a store may index by. Stores persist the values as they
 * are and never interpret them - the meaning of an outcome is the core's business.
 *
 * <p>
 * Why the record keeps a moment the handler ran and a moment the task was last seen open is
 * decision 6 in the repository's DECISIONS.md; why the adapter id is a field of its own is
 * decision 17 in the repository's DECISIONS.md.
 *
 * @param deliveryKey The identity of the delivery, unique within the store: built by
 *          the core from the delivering adapter, the workflow module, the BPMN
 *          process, the event and the delivery ID the adapter reported. A
 *          redelivery of the same task yields the same key, a genuinely new task
 *          instance a different one
 * @param adapterId The ID of the adapter which delivered the task. It is part of the
 *          {@link #deliveryKey()} as well, but only as text and hashed once the key grows
 *          too long, so a store cannot answer questions about it - which is why it is a
 *          field of its own: it lets a store report the adapter ids its
 *          open records belong to, and it tells whoever looks into the store which BPMS
 *          delivered. May be <code>null</code> in a record written before that field
 *          existed
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param workflowAggregateId The workflow aggregate's ID in serialized form
 * @param taskDefinition The task definition (or BPMN activity ID) delivered
 * @param outcome The outcome reported to the BPMS, as the core names it - a
 *          redelivery is answered with exactly this outcome instead of running the
 *          business code again
 * @param bpmnErrorCode The BPMN error code of an outcome carrying one, otherwise
 *          <code>null</code>
 * @param bpmnErrorName The BPMN error name of an outcome carrying one, otherwise
 *          <code>null</code>
 * @param recordedAt When the delivery was processed. It is part of the record so the
 *          core can answer the one question a retention cannot: how long a task has
 *          been open. The value never moves, which is why a store keeps a second
 *          timestamp of its own to delete by (see
 *          {@link TaskDeliveryLog#stillOpen(String)}). A task the BPMS keeps redelivering is
 *          answered from the record it wrote when the handler ran, so the difference
 *          between that moment and now IS the age of the open task, and a task nobody
 *          will ever complete is the only thing that age ever grows into. See
 *          <code>vanillabp.delivery.max-task-age</code>
 */
public record TaskDelivery(
                           String deliveryKey,
                           String adapterId,
                           String workflowModuleId,
                           String bpmnProcessId,
                           String workflowAggregateId,
                           String taskDefinition,
                           String outcome,
                           String bpmnErrorCode,
                           String bpmnErrorName,
                           Instant recordedAt) {

}
