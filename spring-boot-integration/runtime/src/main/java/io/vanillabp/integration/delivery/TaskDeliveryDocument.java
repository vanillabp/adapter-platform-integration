package io.vanillabp.integration.delivery;

import java.time.Instant;

import org.springframework.data.annotation.Id;

/**
 * The record of one processed task delivery in the MongoDB-based
 * {@link io.vanillabp.integration.spi.TaskDeliveryLog}. The delivery key IS the
 * document's ID. So MongoDB enforces the uniqueness which the deduplication needs, and
 * no extra index is needed.
 */
public class TaskDeliveryDocument {

  /**
   * The delivery's identity (see
   * {@link io.vanillabp.integration.spi.TaskDelivery#deliveryKey()}).
   */
  @Id
  private String id;

  /**
   * The adapter which delivered the task. It is also part of the delivery key, but only
   * as text, and hashed once the key grows too long. So a query needs it as a field of
   * its own. Absent in a document written before the field existed.
   */
  private String adapterId;

  /**
   * The workflow module the task belongs to.
   */
  private String workflowModuleId;

  /**
   * The BPMN process the task belongs to.
   */
  private String bpmnProcessId;

  /**
   * The workflow aggregate the task belongs to.
   */
  private String aggregateId;

  /**
   * The BPMS' own id of the workflow this task belongs to. It helps whoever follows a task
   * into the tooling of that engine. Absent where the adapter names no workflow, and in a
   * document written before the field existed.
   */
  private String workflowId;

  /**
   * The task definition which was delivered.
   */
  private String taskDefinition;

  /**
   * The id a modeller wrote on the BPMN element which was delivered. An extension uses it
   * to address a task in the model. Absent where the adapter names no element, and in a
   * document written before the field existed.
   */
  private String bpmnElementId;

  /**
   * The BPMS' identity of the task this delivery was about. The application passes it back
   * to complete or cancel the task. With it, the election can tell from this document
   * which adapter holds that task. Absent in a document written before the field existed.
   */
  private String taskId;

  /**
   * The outcome reported to the BPMS, which a repeated delivery is answered with.
   */
  private String outcome;

  /**
   * The BPMN error code the outcome carries, and <code>null</code> where the outcome
   * is no BPMN error.
   */
  private String bpmnErrorCode;

  /**
   * The name of the BPMN error the outcome carries, and <code>null</code> where the
   * outcome is no BPMN error.
   */
  private String bpmnErrorName;

  /**
   * When the delivery was processed. The age of an open task is measured from here, so
   * this value never moves.
   */
  private Instant recordedAt;

  /**
   * When the BPMS last redelivered the task this record answers. The retention cleanup
   * deletes by it. It is written together with {@link #recordedAt}, and moved forward
   * while an open task keeps being redelivered.
   */
  private Instant lastSeenAt;

  /**
   * When the application's completion or cancellation of this task reached the BPMS, and
   * <code>null</code> while it is still open. Written once, after phase two succeeded.
   */
  private Instant taskClosedAt;

  /**
   * Which kind of task {@link #taskId} is the id of, as the delivering adapter named it:
   * <code>TASK</code> for a task the application works off, <code>USER_TASK</code> for a
   * user task a person works off. The two ids live in namespaces of their own. With this
   * field, VanillaBP can say which method asks for the kind of key a caller named. Absent
   * where the adapter does not say, and in a document written before the field existed.
   */
  private String taskKind;

  /**
   * What this document is about: a task delivery, or the start of a workflow (the names of
   * {@link io.vanillabp.integration.spi.DeliveryRecordKind}). Every question about open work
   * demands that it is not a start. A document without a task would be a phantom task in
   * each of those answers. The field is absent in a document written before it existed, and
   * such a document is a task delivery. That is why those questions ask for "not a start"
   * and not for "a delivery".
   */
  private String recordKind;

  /**
   * The version of the process definition the workflow runs on, as the BPMS counts it (see
   * {@link io.vanillabp.integration.spi.TaskDelivery#processVersion()}). Absent where the adapter
   * does not say and in a document written before this existed.
   */
  private String processVersion;

  /**
   * What Spring Data starts from when it reads a document of the collection. It builds the
   * empty record and fills the fields afterwards. A log which writes a record uses the
   * constructor which takes every field.
   */
  public TaskDeliveryDocument() {
  }

  /**
   * What a log uses to write a record. Every field is known at that moment.
   *
   * @param id The delivery key
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id
   * @param aggregateId The aggregate id
   * @param workflowId The BPMS' workflow id, or <code>null</code>
   * @param taskDefinition The task definition
   * @param bpmnElementId The element id, or <code>null</code>
   * @param taskId The BPMS' task id
   * @param outcome The outcome
   * @param bpmnErrorCode The error code, or <code>null</code>
   * @param bpmnErrorName The error name, or <code>null</code>
   * @param recordedAt The moment the delivery was processed
   * @param lastSeenAt The moment of the last redelivery
   * @param taskClosedAt The moment the task was closed, or <code>null</code>
   * @param taskKind The kind of task the id belongs to, or <code>null</code>
   * @param recordKind What the document is about: a task delivery or the start of a workflow
   * @param processVersion The version of the process definition, or <code>null</code>
   */
  public TaskDeliveryDocument(
      final String id,
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String aggregateId,
      final String workflowId,
      final String taskDefinition,
      final String bpmnElementId,
      final String taskId,
      final String outcome,
      final String bpmnErrorCode,
      final String bpmnErrorName,
      final Instant recordedAt,
      final Instant lastSeenAt,
      final Instant taskClosedAt,
      final String taskKind,
      final String recordKind,
      final String processVersion) {

    this.id = id;
    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.aggregateId = aggregateId;
    this.workflowId = workflowId;
    this.taskDefinition = taskDefinition;
    this.bpmnElementId = bpmnElementId;
    this.taskId = taskId;
    this.outcome = outcome;
    this.bpmnErrorCode = bpmnErrorCode;
    this.bpmnErrorName = bpmnErrorName;
    this.recordedAt = recordedAt;
    this.lastSeenAt = lastSeenAt;
    this.taskClosedAt = taskClosedAt;
    this.taskKind = taskKind;
    this.recordKind = recordKind;
    this.processVersion = processVersion;

  }

  /**
   * The delivery's identity, see {@link #id}
   *
   * @return The delivery key, which is the document's id
   */
  public String getId() {

    return id;

  }

  /**
   * The adapter which delivered the task, see {@link #adapterId}
   *
   * @return The adapter id, or <code>null</code> in a document written before this field existed
   */
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * The workflow module the task belongs to
   *
   * @return The workflow module id
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The BPMN process the task belongs to
   *
   * @return The BPMN process id
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * The workflow aggregate the task belongs to
   *
   * @return The aggregate id
   */
  public String getAggregateId() {

    return aggregateId;

  }

  /**
   * The BPMS' own id of the workflow this task belongs to, see {@link #workflowId}
   *
   * @return The BPMS' workflow id, or <code>null</code> where the adapter names none
   */
  public String getWorkflowId() {

    return workflowId;

  }

  /**
   * The task definition which was delivered
   *
   * @return The task definition
   */
  public String getTaskDefinition() {

    return taskDefinition;

  }

  /**
   * The id a modeller wrote on the BPMN element which was delivered, see {@link
   * #bpmnElementId}
   *
   * @return The element id, or <code>null</code> where the adapter names none
   */
  public String getBpmnElementId() {

    return bpmnElementId;

  }

  /**
   * The BPMS' identity of the task this delivery was about, see {@link #taskId}
   *
   * @return The BPMS' task id, or <code>null</code> in a document written before this field existed
   */
  public String getTaskId() {

    return taskId;

  }

  /**
   * The outcome reported to the BPMS, which a repeated delivery is answered with
   *
   * @return The outcome
   */
  public String getOutcome() {

    return outcome;

  }

  /**
   * The BPMN error code the outcome carries, where the outcome is a BPMN error
   *
   * @return The error code, or <code>null</code> where the outcome is no BPMN error
   */
  public String getBpmnErrorCode() {

    return bpmnErrorCode;

  }

  /**
   * The name of the BPMN error the outcome carries, where the outcome is a BPMN error
   *
   * @return The error name, or <code>null</code> where the outcome is no BPMN error
   */
  public String getBpmnErrorName() {

    return bpmnErrorName;

  }

  /**
   * When the delivery was processed, see {@link #recordedAt}
   *
   * @return The moment the delivery was processed
   */
  public Instant getRecordedAt() {

    return recordedAt;

  }

  /**
   * When the BPMS last redelivered the task this record answers, see {@link
   * #lastSeenAt}
   *
   * @return The moment of the last redelivery
   */
  public Instant getLastSeenAt() {

    return lastSeenAt;

  }

  /**
   * When the application's completion or cancellation reached the BPMS, see {@link
   * #taskClosedAt}
   *
   * @return The moment the task was closed, or <code>null</code> while it is still open
   */
  public Instant getTaskClosedAt() {

    return taskClosedAt;

  }

  /**
   * The kind of task the id of this record belongs to, see {@link #taskKind}
   *
   * @return <code>TASK</code>, <code>USER_TASK</code>, or <code>null</code> where the adapter
   *         did not say
   */
  public String getTaskKind() {

    return taskKind;

  }

  /**
   * The delivery's identity, see {@link #id}
   *
   * @param id The delivery key
   */
  public void setId(
      final String id) {

    this.id = id;

  }

  /**
   * The adapter which delivered the task, see {@link #adapterId}
   *
   * @param adapterId The adapter id
   */
  public void setAdapterId(
      final String adapterId) {

    this.adapterId = adapterId;

  }

  /**
   * The workflow module the task belongs to
   *
   * @param workflowModuleId The workflow module id
   */
  public void setWorkflowModuleId(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  /**
   * The BPMN process the task belongs to
   *
   * @param bpmnProcessId The BPMN process id
   */
  public void setBpmnProcessId(
      final String bpmnProcessId) {

    this.bpmnProcessId = bpmnProcessId;

  }

  /**
   * The workflow aggregate the task belongs to
   *
   * @param aggregateId The aggregate id
   */
  public void setAggregateId(
      final String aggregateId) {

    this.aggregateId = aggregateId;

  }

  /**
   * The BPMS' own id of the workflow this task belongs to, see {@link #workflowId}
   *
   * @param workflowId The BPMS' workflow id, or <code>null</code>
   */
  public void setWorkflowId(
      final String workflowId) {

    this.workflowId = workflowId;

  }

  /**
   * The task definition which was delivered
   *
   * @param taskDefinition The task definition
   */
  public void setTaskDefinition(
      final String taskDefinition) {

    this.taskDefinition = taskDefinition;

  }

  /**
   * The id a modeller wrote on the BPMN element which was delivered, see {@link
   * #bpmnElementId}
   *
   * @param bpmnElementId The element id, or <code>null</code>
   */
  public void setBpmnElementId(
      final String bpmnElementId) {

    this.bpmnElementId = bpmnElementId;

  }

  /**
   * The BPMS' identity of the task this delivery was about, see {@link #taskId}
   *
   * @param taskId The BPMS' task id
   */
  public void setTaskId(
      final String taskId) {

    this.taskId = taskId;

  }

  /**
   * The outcome reported to the BPMS, which a repeated delivery is answered with
   *
   * @param outcome The outcome
   */
  public void setOutcome(
      final String outcome) {

    this.outcome = outcome;

  }

  /**
   * The BPMN error code the outcome carries, where the outcome is a BPMN error
   *
   * @param bpmnErrorCode The error code, or <code>null</code>
   */
  public void setBpmnErrorCode(
      final String bpmnErrorCode) {

    this.bpmnErrorCode = bpmnErrorCode;

  }

  /**
   * The name of the BPMN error the outcome carries, where the outcome is a BPMN error
   *
   * @param bpmnErrorName The error name, or <code>null</code>
   */
  public void setBpmnErrorName(
      final String bpmnErrorName) {

    this.bpmnErrorName = bpmnErrorName;

  }

  /**
   * When the delivery was processed, see {@link #recordedAt}
   *
   * @param recordedAt The moment the delivery was processed
   */
  public void setRecordedAt(
      final Instant recordedAt) {

    this.recordedAt = recordedAt;

  }

  /**
   * When the BPMS last redelivered the task this record answers, see {@link
   * #lastSeenAt}
   *
   * @param lastSeenAt The moment of the last redelivery
   */
  public void setLastSeenAt(
      final Instant lastSeenAt) {

    this.lastSeenAt = lastSeenAt;

  }

  /**
   * When the application's completion or cancellation reached the BPMS, see {@link
   * #taskClosedAt}
   *
   * @param taskClosedAt The moment the task was closed, or <code>null</code>
   */
  public void setTaskClosedAt(
      final Instant taskClosedAt) {

    this.taskClosedAt = taskClosedAt;

  }

  /**
   * The kind of task the id of this record belongs to, see {@link #taskKind}
   *
   * @param taskKind The kind of task, or <code>null</code>
   */
  public void setTaskKind(
      final String taskKind) {

    this.taskKind = taskKind;

  }

  /**
   * What this document is about, see {@link #recordKind}
   *
   * @return The kind of the row, or <code>null</code> in a document written before the field
   *         existed, which is a task delivery
   */
  public String getRecordKind() {

    return recordKind;

  }

  /**
   * What this document is about, see {@link #recordKind}
   *
   * @param recordKind The kind of the row, or <code>null</code>
   */
  public void setRecordKind(
      final String recordKind) {

    this.recordKind = recordKind;

  }

  /**
   * The version of the process definition, see {@link #processVersion}
   *
   * @return The version, or <code>null</code> where the document names none
   */
  public String getProcessVersion() {

    return processVersion;

  }

  /**
   * The version of the process definition, see {@link #processVersion}
   *
   * @param processVersion The version, or <code>null</code>
   */
  public void setProcessVersion(
      final String processVersion) {

    this.processVersion = processVersion;

  }

}
