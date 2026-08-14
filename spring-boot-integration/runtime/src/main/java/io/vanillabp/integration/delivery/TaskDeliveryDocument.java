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
   * When the delivery was processed - the retention cleanup deletes by this field.
   */
  private Instant recordedAt;

}
