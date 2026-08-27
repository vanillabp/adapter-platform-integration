package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single entry of the MongoDB-based phase-two outbox, stored in the collection
 * the configured collection (<code>vanillabp.outbox.mongo.collection</code>). The entry persists the fields of a
 * {@link io.vanillabp.integration.spi.PhaseTwoCall} - the workflow
 * aggregate's ID in its serialized (String) form; conversion back to the aggregate's
 * ID type happens in the core's router at dispatch time.
 * <p>
 * The {@link #idempotencyKey} carries the call's idempotency key (if present) and stays
 * readable for whoever looks at the collection during support. What deduplicates is
 * {@link #dedupKey}, enforced unique by an index on the collection: it holds that same
 * key while the entry waits for its dispatch and the entry's own id from the moment it
 * was dispatched, so the uniqueness spans the operations which are still planned - the
 * storage-level deduplication of the outbox contract. The {@link #status} lifecycle is
 * {@link #STATUS_OPEN} → {@link #STATUS_DONE} (successful dispatch; deleted
 * asynchronously after the configured retention) or {@link #STATUS_BLOCKED} (too many
 * failed attempts; manual cleanup required).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PhaseTwoOutboxEntry {

  public static final String STATUS_OPEN = "OPEN";

  public static final String STATUS_DONE = "DONE";

  public static final String STATUS_BLOCKED = "BLOCKED";

  @Id
  private String id;

  private String workflowModuleId;

  private String bpmnProcessId;

  /**
   * The name of the scheduled
   * {@link io.vanillabp.integration.spi.PhaseTwoOperation}.
   */
  private String operation;

  private String aggregateId;

  /**
   * The ID of the BPMS adapter elected at scheduling time (may be
   * <code>null</code> for future probing operations).
   */
  private String adapterId;

  private Map<String, String> args;

  /**
   * The call's idempotency key; <code>null</code> if the operation must not be
   * deduplicated. Descriptive only - it is never used to look an entry up.
   */
  private String idempotencyKey;

  /**
   * What the unique index spans: the idempotency key while the entry waits for its
   * dispatch, the entry's own {@link #id} once it was dispatched or where the operation
   * must not be deduplicated at all. Never <code>null</code>, so a database treating
   * two nulls as equal cannot refuse the second keyless entry.
   */
  private String dedupKey;

  private String status;

  private Instant createdAt;

  private int attempts;

  private Instant nextAttemptAt;

  private Instant doneAt;

}
