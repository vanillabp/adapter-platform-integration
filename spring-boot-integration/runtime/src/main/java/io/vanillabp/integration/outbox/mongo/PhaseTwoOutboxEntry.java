package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;

import org.springframework.data.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single entry of the MongoDB-based phase-two outbox, stored in the collection
 * {@link MongoPhaseTwoOutbox#COLLECTION}. The workflow aggregate's ID is stored in
 * serialized form ({@link #aggregateId}) together with its original type
 * ({@link #aggregateIdType}) so it can be converted back before dispatching.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PhaseTwoOutboxEntry {

  @Id
  private String id;

  private String workflowModuleId;

  private String bpmnProcessId;

  /**
   * The scheduled operation (see the <code>OPERATION_*</code> constants of
   * {@link MongoPhaseTwoOutbox}), determining which {@link
   * io.vanillabp.integration.adapter.spi.PhaseTwoDispatch} method is called.
   */
  private String operation;

  private String aggregateId;

  private String aggregateIdType;

  private Instant createdAt;

  private int attempts;

  private Instant nextAttemptAt;

}
