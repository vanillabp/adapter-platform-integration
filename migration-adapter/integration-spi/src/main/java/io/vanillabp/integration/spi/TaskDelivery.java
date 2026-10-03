package io.vanillabp.integration.spi;

import java.time.Instant;

/**
 * What VanillaBP remembers about a task delivery it processed: the delivery's
 * identity plus the outcome reported to the BPMS. Written by the core through the
 * {@link TaskDeliveryLog} within the transaction which also persists the workflow
 * aggregate, and read again when the BPMS delivers the same task a second time (see
 * the deduplication contract of {@link TaskDeliveryLog}).
 * <p>
 * A row may also be about the START of a workflow rather than about a delivery, and
 * {@link #recordKind()} is what says which of the two it is. Such a row carries the workflow
 * aggregate and the BPMS' own id of its workflow and nothing else - see
 * {@link #workflowStart(String, String, String, String, String, Instant)}.
 * <p>
 * Everything besides {@link #deliveryKey()} and {@link #outcome()} is context: it
 * makes a record readable for whoever looks into the store while investigating a
 * workflow, and it is what a store may index by. Stores persist the values as they
 * are and never interpret them - the meaning of an outcome is the core's business.
 *
 * <p>
 * Why the record keeps a moment the handler ran and a moment the task was last seen open is
 * decision 6 in the repository's DECISIONS.md; why the adapter id is a field of its own is
 * decision 17 in the repository's DECISIONS.md; why the task and the moment it was closed are
 * fields of their own is decision 30 in the repository's DECISIONS.md.
 *
 * @param deliveryKey The identity of the delivery, unique within the store: built by
 *          the core from the delivering adapter, the workflow module, the BPMN
 *          process, the event and the delivery ID the adapter reported. A
 *          redelivery of the same task yields the same key, a genuinely new task
 *          instance a different one. Where the adapter reports no delivery ID the key
 *          says so in words and carries a random value, so it belongs to this one record
 *          and no second delivery is ever looked up by it
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
 * @param workflowId The BPMS' own id of the workflow the task belongs to - the running
 *          instance as the engine counts it, not the workflow aggregate's id. VanillaBP
 *          addresses a workflow by its aggregate, so nothing here depends on this value.
 *          It is carried for whoever looks at the task outside VanillaBP: an operator in
 *          the engine's own cockpit, or an extension linking into it. May be
 *          <code>null</code> where the adapter names no workflow or the record predates
 *          this field
 * @param taskDefinition The task definition (or BPMN activity ID) delivered
 * @param bpmnElementId The <code>id</code> attribute of the BPMN element which was
 *          delivered, what a modeller wrote on it. {@link #taskDefinition()} carries the
 *          same text only where the BPMN task names no task definition, because a handler
 *          is resolved by either of the two; a Camunda 8 job type or a Camunda 7 topic
 *          stands there otherwise. So this is the field which always names the element,
 *          and it is what an extension addresses a task in the model by. May be
 *          <code>null</code> where the adapter names no element or the record predates
 *          this field
 * @param taskId The BPMS' identity of the task this delivery was about - what the
 *          application receives in a <code>&#64;TaskId</code> parameter and passes back to
 *          <code>ProcessService#completeTask</code>. It is what lets VanillaBP answer from
 *          this record which adapter holds a task instead of asking every BPMS, so a store
 *          which can be queried by it saves a round trip per task operation. May be
 *          <code>null</code> where the BPMS names no task or the record predates this field
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
 * @param taskClosedAt When the application's completion or cancellation of this task
 *          reached the BPMS - <code>null</code> for as long as the task is still open. It is
 *          written after phase two succeeded and not when the caller asked, because between
 *          the two the task is still open and its redeliveries still renew the BPMS' lock on
 *          it. Once it is set, a second completion of the same task is the warned no-op it
 *          always was, and no BPMS has to be asked for that either
 * @param recordKind What this row is about: a task delivery, or the start of a workflow (the
 *          names of {@link DeliveryRecordKind}). It is the KIND of the row and not a result, so
 *          {@link #outcome()} keeps meaning what a delivery reported and a start row leaves it
 *          empty. Every question about open work filters on it, because a start row carries no
 *          task and would be a phantom task in each of those answers. May be <code>null</code>
 *          in a record written before the field existed, which is a task delivery
 * @param taskKind Which kind of task {@link #taskId()} is the id of, as the delivering
 *          adapter named it: <code>TASK</code> for a task the application works off,
 *          <code>USER_TASK</code> for a user task a person works off (the names of
 *          {@code io.vanillabp.integration.adapter.spi.workflowtask.TaskKind}). The two ids
 *          live in namespaces of their own, so an id handed to the command of the other kind
 *          is answered with "not found" for a task which is perfectly alive - and this is
 *          what lets VanillaBP say which kind the id is instead of listing what it could
 *          be. A store keeps the text as it is. May be <code>null</code> where the adapter
 *          does not say or the record predates this field, and then the record is left out
 *          of that answer
 */
public record TaskDelivery(
                           String deliveryKey,
                           String adapterId,
                           String workflowModuleId,
                           String bpmnProcessId,
                           String workflowAggregateId,
                           String workflowId,
                           String taskDefinition,
                           String bpmnElementId,
                           String taskId,
                           String outcome,
                           String bpmnErrorCode,
                           String bpmnErrorName,
                           Instant recordedAt,
                           Instant taskClosedAt,
                           String taskKind,
                           String recordKind) {

  /**
   * The row about the START of a workflow: the workflow aggregate and the BPMS' own id of the
   * workflow it runs as, and nothing about a task.
   * <p>
   * It is written in the transaction which persists the aggregate, under a key no delivery can
   * ever fall on ({@link WorkflowStartKey}), and it is what lets the BPMS election and an
   * extension read that id without asking a BPMS. The id is the SUPER-PARENT instance - the
   * workflow of this aggregate - so the instances a call activity creates underneath are not in
   * it; those stand on the task they are delivered with.
   * <p>
   * {@link #outcome()} stays empty, because a start reports nothing. So do the task, the task
   * definition, the element and the kind of task: a start is not a delivery and the row says so
   * in {@link #recordKind()}.
   *
   * @param adapterId The ID of the adapter which started the workflow
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param workflowId The BPMS' own id of the started workflow
   * @param startedAt When the workflow was started
   * @return The row to hand to {@link TaskDeliveryLog#record(TaskDelivery)}
   */
  public static TaskDelivery workflowStart(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String workflowId,
      final Instant startedAt) {

    return new TaskDelivery(
        WorkflowStartKey.of(workflowModuleId, bpmnProcessId,
            workflowAggregateId), adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, workflowId, null, null, null, null, null, null, startedAt, null, null, DeliveryRecordKind.WORKFLOW_START
                .name());

  }

  /**
   * A record of a task delivery, which is what every caller wrote before a row said its kind.
   *
   * @param deliveryKey The identity of the delivery
   * @param adapterId The ID of the adapter which delivered the task
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param workflowId The BPMS' own id of the workflow the task belongs to
   * @param taskDefinition The task definition (or BPMN activity ID) delivered
   * @param bpmnElementId The <code>id</code> attribute of the BPMN element delivered
   * @param taskId The BPMS' identity of the task this delivery was about
   * @param outcome The outcome reported to the BPMS, as the core names it
   * @param bpmnErrorCode The BPMN error code of an outcome carrying one
   * @param bpmnErrorName The BPMN error name of an outcome carrying one
   * @param recordedAt When the delivery was processed
   * @param taskClosedAt When the completion of this task reached the BPMS
   * @param taskKind Which kind of task the id belongs to
   */
  public TaskDelivery(
      final String deliveryKey,
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String workflowId,
      final String taskDefinition,
      final String bpmnElementId,
      final String taskId,
      final String outcome,
      final String bpmnErrorCode,
      final String bpmnErrorName,
      final Instant recordedAt,
      final Instant taskClosedAt,
      final String taskKind) {

    this(
        deliveryKey, adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, workflowId, taskDefinition, bpmnElementId, taskId, outcome, bpmnErrorCode, bpmnErrorName, recordedAt, taskClosedAt, taskKind, DeliveryRecordKind.TASK_DELIVERY
            .name());

  }

  /**
   * A record which names no kind of task, which is what every caller wrote before the kind
   * existed.
   * <p>
   * {@link #taskKind()} stands behind the timestamps although it belongs next to
   * {@link #taskId()}, and {@link #recordKind()} behind it: each was added to a record whose
   * other components every store and every test already writes, and appending keeps those
   * callers as they are. The same reason put
   * {@code io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec#name()} at the end
   * of its record.
   *
   * @param deliveryKey The identity of the delivery
   * @param adapterId The ID of the adapter which delivered the task
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param workflowId The BPMS' own id of the workflow the task belongs to
   * @param taskDefinition The task definition (or BPMN activity ID) delivered
   * @param bpmnElementId The <code>id</code> attribute of the BPMN element delivered
   * @param taskId The BPMS' identity of the task this delivery was about
   * @param outcome The outcome reported to the BPMS, as the core names it
   * @param bpmnErrorCode The BPMN error code of an outcome carrying one
   * @param bpmnErrorName The BPMN error name of an outcome carrying one
   * @param recordedAt When the delivery was processed
   * @param taskClosedAt When the completion of this task reached the BPMS
   */
  public TaskDelivery(
      final String deliveryKey,
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String workflowId,
      final String taskDefinition,
      final String bpmnElementId,
      final String taskId,
      final String outcome,
      final String bpmnErrorCode,
      final String bpmnErrorName,
      final Instant recordedAt,
      final Instant taskClosedAt) {

    this(
        deliveryKey, adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, workflowId, taskDefinition, bpmnElementId, taskId, outcome, bpmnErrorCode, bpmnErrorName, recordedAt, taskClosedAt, null);

  }

}
