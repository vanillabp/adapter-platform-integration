package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * A single entry of the MongoDB-based phase-two outbox, stored in the configured
 * collection (<code>vanillabp.outbox.mongo.collection</code>). The entry persists the fields of a
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
@AllArgsConstructor
public class PhaseTwoOutboxEntry {

  /**
   * An entry which is still waiting for its dispatch. Its {@link #dedupKey} is the
   * idempotency key, so a second call planning the same operation is discarded while this
   * entry stands.
   */
  public static final String STATUS_OPEN = "OPEN";

  /**
   * An entry which reached the BPMS. It is kept for <code>vanillabp.outbox.retention</code>
   * and deleted afterwards, so support can still read what was dispatched, and its
   * {@link #dedupKey} is its own id by then - the deduplication ends where the dispatch is
   * over (see decision 22 in the repository's DECISIONS.md).
   */
  public static final String STATUS_DONE = "DONE";

  /**
   * An entry which used up <code>vanillabp.outbox.block-after-attempts</code> attempts, or
   * whose failure the adapter called permanent. It waits for a person: no poll takes it
   * again and no retention deletes it, which is why it is the one status somebody has to
   * clean up by hand.
   */
  public static final String STATUS_BLOCKED = "BLOCKED";

  @Id
  private String id;

  private String workflowModuleId;

  private String bpmnProcessId;

  /**
   * The name of the scheduled
   * {@link io.vanillabp.integration.spi.PhaseOperation}.
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

  /**
   * How many dispatch attempts of this entry ENDED, whatever they ended with. Not how often
   * it was claimed: an entry being dispatched right now has counted nothing yet, which is
   * what keeps <code>vanillabp.outbox.block-after-attempts</code> from blocking an entry for
   * being slow.
   */
  private int attempts;

  private Instant nextAttemptAt;

  private Instant doneAt;

  /**
   * Which node is dispatching this entry, <code>null</code> for an entry nobody holds. It
   * is what a renewal of the lease matches on, so a node whose lease ran out and was taken
   * over renews nothing.
   */
  private String leasedBy;

  /**
   * How long the claim on this entry lasts. No poll takes an entry whose lease has not run
   * out, and a running dispatch pushes the moment along for as long as it runs (see
   * {@link io.vanillabp.integration.adapter.migration.outbox.DispatchLease}).
   * <code>null</code> for an entry nobody holds.
   */
  private Instant leasedUntil;

  /**
   * What Spring Data starts from when it reads an entry of the collection: it builds the
   * empty entry and fills the fields afterwards. The outbox writing an entry uses the
   * constructor taking every field, which Lombok generates.
   */
  public PhaseTwoOutboxEntry() {
  }

}
