package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;

import org.springframework.data.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The payload of one phase-two call in the MongoDB-based outbox. The reference IS the
 * document's id, so the lookup at dispatch time is a read by primary key.
 * <p>
 * The bytes are a field of this document and not a file in GridFS, which bounds a
 * payload at what MongoDB holds in one document, 16 MB. VanillaBP bounds it far below
 * that ({@link io.vanillabp.integration.spi.PhaseTwoCall#MAX_PAYLOAD_SIZE}).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PhaseTwoPayloadDocument {

  /**
   * The reference the outbox entry names this payload by.
   */
  @Id
  private String id;

  private String workflowModuleId;

  private String bpmnProcessId;

  /**
   * The name of the operation the payload belongs to - for whoever reads the
   * collection during support.
   */
  private String operation;

  private byte[] payload;

  /**
   * When the payload was written. The housekeeping deletes by it, which is how a
   * document whose entry never came into being disappears again.
   */
  private Instant createdAt;

}
