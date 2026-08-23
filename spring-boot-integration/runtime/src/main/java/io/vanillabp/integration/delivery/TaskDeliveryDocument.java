package io.vanillabp.integration.delivery;

import java.time.Instant;

import org.springframework.data.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The record of one processed task delivery in the MongoDB-based
 * {@link io.vanillabp.integration.spi.TaskDeliveryLog}. The delivery key IS the
 * document's ID, so MongoDB enforces the uniqueness the deduplication needs without an
 * index of its own.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TaskDeliveryDocument {

  /**
   * The delivery's identity (see
   * {@link io.vanillabp.integration.spi.TaskDelivery#deliveryKey()}).
   */
  @Id
  private String id;

  /**
   * The adapter which delivered the task (story 120). Part of the delivery key as well,
   * but only as text and hashed once the key grows too long, so a query needs it as a
   * field of its own. Absent in a document written before it existed.
   */
  private String adapterId;

  private String workflowModuleId;

  private String bpmnProcessId;

  private String aggregateId;

  private String taskDefinition;

  /**
   * The outcome reported to the BPMS, which a repeated delivery is answered with.
   */
  private String outcome;

  private String bpmnErrorCode;

  private String bpmnErrorName;

  /**
   * When the delivery was processed. The age of an open task is measured from here, so
   * this value never moves.
   */
  private Instant recordedAt;

  /**
   * When the BPMS last redelivered the task this record answers, which is what the
   * retention cleanup deletes by (story 97). Written together with
   * {@link #recordedAt} and moved forward while an open task keeps being redelivered.
   */
  private Instant lastSeenAt;

}
