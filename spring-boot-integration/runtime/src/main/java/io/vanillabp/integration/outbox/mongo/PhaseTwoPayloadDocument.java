package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;

import org.springframework.data.annotation.Id;

/**
 * The payload of one phase-two call in the MongoDB-based outbox. The reference IS the
 * document's id, so the lookup at dispatch time is a read by primary key.
 * <p>
 * The bytes are a field of this document and not a file in GridFS, which bounds a
 * payload at what MongoDB holds in one document, 16 MB. VanillaBP bounds it far below
 * that ({@link io.vanillabp.integration.spi.PhaseTwoCall#MAX_PAYLOAD_SIZE}).
 */
public class PhaseTwoPayloadDocument {

  /**
   * The reference the outbox entry names this payload by.
   */
  @Id
  private String id;

  /**
   * The workflow module the call belongs to.
   */
  private String workflowModuleId;

  /**
   * The BPMN process the call belongs to.
   */
  private String bpmnProcessId;

  /**
   * The name of the operation the payload belongs to - for whoever reads the
   * collection during support.
   */
  private String operation;

  /**
   * The bytes handed to the adapter when the call is dispatched.
   */
  private byte[] payload;

  /**
   * When the payload was written. The housekeeping deletes by it, which is how a
   * document whose entry never came into being disappears again.
   */
  private Instant createdAt;

  /**
   * What Spring Data starts from when it reads a payload document: it builds the empty
   * document and fills the fields afterwards. The store writing a payload uses the
   * constructor taking every field.
   */
  public PhaseTwoPayloadDocument() {
  }

  /**
   * What the store writing a payload uses: every field is known at that moment.
   *
   * @param id The reference the outbox entry names this payload by
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation The name of the operation the payload belongs to
   * @param payload The bytes handed to the adapter when the call is dispatched
   * @param createdAt When the payload was written
   */
  public PhaseTwoPayloadDocument(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final byte[] payload,
      final Instant createdAt) {

    this.id = id;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.operation = operation;
    this.payload = payload;
    this.createdAt = createdAt;

  }

  /**
   * The reference the outbox entry names this payload by.
   *
   * @return The document's id
   */
  public String getId() {

    return id;

  }

  /**
   * The workflow module the call belongs to.
   *
   * @return The workflow module id
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The BPMN process the call belongs to.
   *
   * @return The BPMN process id
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * The name of the operation the payload belongs to.
   *
   * @return The operation's name
   */
  public String getOperation() {

    return operation;

  }

  /**
   * The bytes handed to the adapter when the call is dispatched.
   *
   * @return The payload
   */
  public byte[] getPayload() {

    return payload;

  }

  /**
   * When the payload was written.
   *
   * @return The moment the document came into being
   */
  public Instant getCreatedAt() {

    return createdAt;

  }

  /**
   * The reference the outbox entry names this payload by.
   *
   * @param id The document's id
   */
  public void setId(
      final String id) {

    this.id = id;

  }

  /**
   * The workflow module the call belongs to.
   *
   * @param workflowModuleId The workflow module id
   */
  public void setWorkflowModuleId(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  /**
   * The BPMN process the call belongs to.
   *
   * @param bpmnProcessId The BPMN process id
   */
  public void setBpmnProcessId(
      final String bpmnProcessId) {

    this.bpmnProcessId = bpmnProcessId;

  }

  /**
   * The name of the operation the payload belongs to.
   *
   * @param operation The operation's name
   */
  public void setOperation(
      final String operation) {

    this.operation = operation;

  }

  /**
   * The bytes handed to the adapter when the call is dispatched.
   *
   * @param payload The payload
   */
  public void setPayload(
      final byte[] payload) {

    this.payload = payload;

  }

  /**
   * When the payload was written.
   *
   * @param createdAt The moment the document came into being
   */
  public void setCreatedAt(
      final Instant createdAt) {

    this.createdAt = createdAt;

  }

}
