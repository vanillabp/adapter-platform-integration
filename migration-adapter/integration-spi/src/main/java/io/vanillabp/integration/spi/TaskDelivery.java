package io.vanillabp.integration.spi;

import java.time.Instant;

/**
 * What VanillaBP remembers about a task delivery it processed: the identity of the
 * delivery, plus the outcome reported to the BPMS. The core writes it through the
 * {@link TaskDeliveryLog}, inside the transaction which also persists the workflow
 * aggregate. It is read again when the BPMS delivers the same task a second time (see
 * the deduplication contract of {@link TaskDeliveryLog}).
 * <p>
 * A row may also be about the START of a workflow instead of a delivery.
 * {@link #recordKind()} says which of the two it is. Such a row carries the workflow
 * aggregate, the BPMS' own id of its workflow and the version of the process definition it was
 * started on, and nothing else. See
 * {@link #workflowStart(String, String, String, String, String, String, Instant)}.
 * <p>
 * Everything except {@link #deliveryKey()} and {@link #outcome()} is context. It makes a
 * record readable for whoever looks into the store to investigate a workflow, and a store
 * may index by it. Stores persist the values as they are and never interpret them. What
 * an outcome means is up to the core.
 *
 * <p>
 * Why the record keeps the moment the handler ran and the moment the task was last seen open is
 * decision 6 in the repository's DECISIONS.md; why the adapter id is a field of its own is
 * decision 17 in the repository's DECISIONS.md; why the task and the moment it was closed are
 * fields of their own is decision 30 in the repository's DECISIONS.md.
 *
 * @param deliveryKey The identity of the delivery, unique within the store. The core
 *          builds it from the delivering adapter, the workflow module, the BPMN process,
 *          the event and the delivery ID the adapter reported. A redelivery of the same
 *          task gives the same key, a really new task instance a different one. If the
 *          adapter reports no delivery ID, the key says so in words and carries a random
 *          value. So it belongs to this one record, and no second delivery is ever looked
 *          up by it
 * @param adapterId The ID of the adapter which delivered the task. It is also part of the
 *          {@link #deliveryKey()}, but only as text, and it is hashed once the key grows too
 *          long. So a store cannot answer questions about it, and that is why it is a field
 *          of its own. With it, a store can report the adapter ids its open records belong
 *          to, and whoever looks into the store sees which BPMS delivered. May be
 *          <code>null</code> in a record written before that field existed
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID. For a delivery it is the process the task belongs
 *          to. So for a task of a called process, it is the called process and not the one at
 *          the top. For the row about a start, it is the process the workflow was started with
 * @param workflowAggregateId The workflow aggregate's ID in serialized form
 * @param workflowId The BPMS' own id of the workflow the task belongs to. That is the running
 *          instance as the engine counts it, not the id of the workflow aggregate. VanillaBP
 *          addresses a workflow by its aggregate, so nothing here depends on this value.
 *          It is carried for whoever looks at the task outside VanillaBP: an operator in
 *          the own cockpit of the engine, or an extension which links into it. May be
 *          <code>null</code> if the adapter names no workflow or the record is older than
 *          this field
 * @param taskDefinition The task definition (or BPMN activity ID) delivered
 * @param bpmnElementId The <code>id</code> attribute of the BPMN element which was
 *          delivered, as a modeller wrote it. {@link #taskDefinition()} carries the same
 *          text only if the BPMN task names no task definition, because a handler is found
 *          by either of the two. Otherwise it holds a Camunda 8 job type or a Camunda 7
 *          topic. So this is the field which always names the element, and an extension
 *          uses it to address a task in the model. May be <code>null</code> if the adapter
 *          names no element or the record is older than this field
 * @param taskId The BPMS' identity of the task this delivery was about. The application
 *          receives it in a <code>&#64;TaskId</code> parameter and passes it back to
 *          <code>ProcessService#completeTask</code>. With it, VanillaBP can answer from this
 *          record which adapter holds a task, instead of asking every BPMS. So a store which
 *          can be queried by it saves a round trip per task operation. May be
 *          <code>null</code> if the BPMS names no task or the record is older than this field
 * @param outcome The outcome reported to the BPMS, as the core names it. A redelivery is
 *          answered with exactly this outcome, instead of running the business code again
 * @param bpmnErrorCode The BPMN error code of an outcome carrying one, otherwise
 *          <code>null</code>
 * @param bpmnErrorName The BPMN error name of an outcome carrying one, otherwise
 *          <code>null</code>
 * @param recordedAt When the delivery was processed. It is part of the record, so the
 *          core can answer the one question a retention cannot: how long a task has
 *          been open. The value never moves. That is why a store keeps a second timestamp
 *          of its own to delete by (see {@link TaskDeliveryLog#stillOpen(String)}). If the
 *          BPMS keeps redelivering a task, the answer comes from the record written when the
 *          handler ran. So the difference between that moment and now IS the age of the
 *          open task. Only a task nobody will ever complete keeps growing older. See
 *          <code>vanillabp.delivery.max-task-age</code>
 * @param taskClosedAt When the completion or cancellation of this task by the application
 *          reached the BPMS. It is <code>null</code> as long as the task is still open. It
 *          is written after phase two succeeded, and not when the caller asked. Between the
 *          two the task is still open, and its redeliveries still renew the lock of the BPMS
 *          on it. Once it is set, a second completion of the same task does nothing except
 *          log a warning, as it always did, and no BPMS has to be asked for that either
 * @param taskKind Which kind of task {@link #taskId()} is the id of, as the delivering
 *          adapter named it: <code>TASK</code> for a task the application works off,
 *          <code>USER_TASK</code> for a user task a person works off (the names of
 *          {@code io.vanillabp.integration.adapter.spi.workflowtask.TaskKind}). The two kinds
 *          of id live in namespaces of their own. So an id passed to the command of the other
 *          kind is answered with "not found", although the task is alive. With this field,
 *          VanillaBP can say which kind the id is, instead of listing what it could be. A
 *          store keeps the text as it is. May be <code>null</code> if the adapter does not say
 *          or the record is older than this field. Then the record is left out of that answer
 * @param recordKind What this row is about: a task delivery, or the start of a workflow (the
 *          names of {@link DeliveryRecordKind}). It is the KIND of the row and not a result. So
 *          {@link #outcome()} keeps meaning what a delivery reported, and a start row leaves it
 *          empty. Every question about open work filters on it, because a start row carries no
 *          task and would show up as a phantom task in each of those answers. May be
 *          <code>null</code> in a record written before the field existed. Such a record is a
 *          task delivery
 * @param processVersion The version of the process definition the workflow runs on, as the
 *          BPMS counts it, in the form the adapter reports it for a task (a number on Camunda 7
 *          and Camunda 8, a version tag if the BPMS has only that). It is written for a
 *          delivery and for the start of a workflow alike. An extension which shows details per
 *          version needs it from the first moment on, and a BPMS which answers from a read
 *          model cannot give it then. May be <code>null</code> if the adapter does not say or
 *          the record is older than this field
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
                           String recordKind,
                           String processVersion) {

  /**
   * The row about the START of a workflow. It holds the workflow aggregate, the BPMS' own id of
   * the workflow it runs as, and the version of the process definition it was started on. It
   * holds nothing about a task.
   * <p>
   * It is written in the transaction which persists the aggregate, under a key no delivery can
   * ever get ({@link WorkflowStartKey}). With it, the BPMS election and an extension can read
   * that id without asking a BPMS. The id is the SUPER-PARENT instance, the workflow of this
   * aggregate. So the instances a call activity creates below it are not in it. Those are on
   * the task they are delivered with.
   * <p>
   * {@link #outcome()} stays empty, because a start reports nothing. So do the task, the task
   * definition, the element and the kind of task. A start is not a delivery, and the row says
   * so in {@link #recordKind()}.
   *
   * @param adapterId The ID of the adapter which started the workflow
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param workflowId The BPMS' own id of the started workflow
   * @param processVersion The version of the process definition the workflow was started on, or
   *          <code>null</code> if the adapter does not say
   * @param startedAt When the workflow was started
   * @return The row to hand to {@link TaskDeliveryLog#recordWorkflowStart(TaskDelivery)}
   */
  public static TaskDelivery workflowStart(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String workflowId,
      final String processVersion,
      final Instant startedAt) {

    return new TaskDelivery(
        WorkflowStartKey.of(workflowModuleId, bpmnProcessId,
            workflowAggregateId), adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, workflowId, null, null, null, null, null, null, startedAt, null, null, DeliveryRecordKind.WORKFLOW_START
                .name(), processVersion);

  }

  /**
   * The row about the START of a workflow without a version. Every caller wrote this before
   * the version existed.
   *
   * @param adapterId The ID of the adapter which started the workflow
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param workflowId The BPMS' own id of the started workflow
   * @param startedAt When the workflow was started
   * @return The row to hand to {@link TaskDeliveryLog#recordWorkflowStart(TaskDelivery)}
   */
  public static TaskDelivery workflowStart(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String workflowId,
      final Instant startedAt) {

    return workflowStart(adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, workflowId, null, startedAt);

  }

  /**
   * A record without a process version. Every caller wrote this before the version existed.
   * {@link #processVersion()} is the last component, for the reason the last constructor
   * gives for {@link #taskKind()} and {@link #recordKind()}.
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
   * @param recordKind What this row is about
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
      final String taskKind,
      final String recordKind) {

    this(
        deliveryKey, adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, workflowId, taskDefinition, bpmnElementId, taskId, outcome, bpmnErrorCode, bpmnErrorName, recordedAt, taskClosedAt, taskKind, recordKind, null);

  }

  /**
   * A record of a task delivery. Every caller wrote this before a row said its kind.
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
   * A record without a kind of task. Every caller wrote this before the kind existed.
   * <p>
   * {@link #taskKind()} comes after the timestamps, although it belongs next to
   * {@link #taskId()}, and {@link #recordKind()} comes after it. Each was added to a record
   * whose other components every store and every test already writes. Appending keeps
   * those callers as they are. For the same reason
   * {@code io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec#name()} is at the
   * end of its record.
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
