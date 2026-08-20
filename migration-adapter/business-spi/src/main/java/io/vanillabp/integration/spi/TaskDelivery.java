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
 * @param deliveryKey The identity of the delivery, unique within the store: built by
 *          the core from the delivering adapter, the workflow module, the BPMN
 *          process, the event and the delivery ID the adapter reported. A
 *          redelivery of the same task yields the same key, a genuinely new task
 *          instance a different one
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
 * @param recordedAt When the delivery was processed. Every store persisted this
 *          value from the beginning, because the retention deletes by it; it is part
 *          of the record so the core can answer the one question a retention cannot:
 *          how long a task has been open. A task the BPMS keeps redelivering is
 *          answered from the record it wrote when the handler ran, so the difference
 *          between that moment and now IS the age of the open task, and a task nobody
 *          will ever complete is the only thing that age ever grows into. See
 *          <code>vanillabp.delivery.max-task-age</code>
 */
public record TaskDelivery(
                           String deliveryKey,
                           String workflowModuleId,
                           String bpmnProcessId,
                           String workflowAggregateId,
                           String taskDefinition,
                           String outcome,
                           String bpmnErrorCode,
                           String bpmnErrorName,
                           Instant recordedAt) {

}
