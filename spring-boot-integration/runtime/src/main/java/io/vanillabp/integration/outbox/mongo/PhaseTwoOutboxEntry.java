package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;

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

  /**
   * The entry's own id, which MongoDB keeps unique and which {@link #dedupKey} holds
   * once the entry was dispatched.
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
   * The name of the scheduled
   * {@link io.vanillabp.integration.spi.PhaseOperation}.
   */
  private String operation;

  /**
   * The workflow aggregate the call belongs to, in its serialized form. The core's
   * router converts it back to the aggregate's id type at dispatch time.
   */
  private String aggregateId;

  /**
   * The ID of the BPMS adapter elected at scheduling time (may be
   * <code>null</code> for future probing operations).
   */
  private String adapterId;

  /**
   * What the operation is called with, as the scheduling side handed it over.
   */
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

  /**
   * Where the entry stands: {@link #STATUS_OPEN}, {@link #STATUS_DONE} or
   * {@link #STATUS_BLOCKED}.
   */
  private String status;

  /**
   * When the call was scheduled.
   */
  private Instant createdAt;

  /**
   * How many dispatch attempts of this entry ENDED, whatever they ended with. Not how often
   * it was claimed: an entry being dispatched right now has counted nothing yet, which is
   * what keeps <code>vanillabp.outbox.block-after-attempts</code> from blocking an entry for
   * being slow.
   */
  private int attempts;

  /**
   * When the next dispatch attempt may start. A poll passes over an entry which is not
   * due yet.
   */
  private Instant nextAttemptAt;

  /**
   * When the dispatch reached the BPMS, which the retention deletes by.
   * <code>null</code> while the entry is still open.
   */
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
   * constructor taking every field.
   */
  public PhaseTwoOutboxEntry() {
  }

  /**
   * What the outbox writing an entry uses: every field is known at that moment.
   *
   * @param id The entry id
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id
   * @param operation The operation's name
   * @param aggregateId The aggregate id as text
   * @param adapterId The adapter id, or <code>null</code>
   * @param args The arguments of the call
   * @param idempotencyKey The idempotency key, or <code>null</code>
   * @param dedupKey The key the index holds, never <code>null</code>
   * @param status The status
   * @param createdAt The moment the entry was written
   * @param attempts The number of ended attempts
   * @param nextAttemptAt The moment the entry becomes due again
   * @param doneAt The moment the entry was dispatched, or <code>null</code>
   * @param leasedBy The node holding the entry, or <code>null</code>
   * @param leasedUntil The moment the lease runs out, or <code>null</code>
   */
  public PhaseTwoOutboxEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final Map<String, String> args,
      final String idempotencyKey,
      final String dedupKey,
      final String status,
      final Instant createdAt,
      final int attempts,
      final Instant nextAttemptAt,
      final Instant doneAt,
      final String leasedBy,
      final Instant leasedUntil) {

    this.id = id;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.operation = operation;
    this.aggregateId = aggregateId;
    this.adapterId = adapterId;
    this.args = args;
    this.idempotencyKey = idempotencyKey;
    this.dedupKey = dedupKey;
    this.status = status;
    this.createdAt = createdAt;
    this.attempts = attempts;
    this.nextAttemptAt = nextAttemptAt;
    this.doneAt = doneAt;
    this.leasedBy = leasedBy;
    this.leasedUntil = leasedUntil;

  }

  /**
   * The entry's own id, which MongoDB keeps unique
   *
   * @return The entry id
   */
  public String getId() {

    return id;

  }

  /**
   * The workflow module the call belongs to
   *
   * @return The workflow module id
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The BPMN process the call belongs to
   *
   * @return The BPMN process id
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * The name of the scheduled {@link io.vanillabp.integration.spi.PhaseOperation}
   *
   * @return The operation's name
   */
  public String getOperation() {

    return operation;

  }

  /**
   * The workflow aggregate the call belongs to, in its serialized form
   *
   * @return The aggregate id as text
   */
  public String getAggregateId() {

    return aggregateId;

  }

  /**
   * The id of the BPMS adapter elected at scheduling time, see {@link #adapterId}
   *
   * @return The adapter id, or <code>null</code> for a future probing operation
   */
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * What the operation is called with
   *
   * @return The arguments of the call
   */
  public Map<String, String> getArgs() {

    return args;

  }

  /**
   * The call's idempotency key, see {@link #idempotencyKey}
   *
   * @return The idempotency key, or <code>null</code> where the operation must not be deduplicated
   */
  public String getIdempotencyKey() {

    return idempotencyKey;

  }

  /**
   * What the unique index spans, see {@link #dedupKey}
   *
   * @return The key the index holds, never <code>null</code>
   */
  public String getDedupKey() {

    return dedupKey;

  }

  /**
   * Where the entry stands: {@link #STATUS_OPEN}, {@link #STATUS_DONE} or {@link
   * #STATUS_BLOCKED}
   *
   * @return The status
   */
  public String getStatus() {

    return status;

  }

  /**
   * When the call was scheduled
   *
   * @return The moment the entry was written
   */
  public Instant getCreatedAt() {

    return createdAt;

  }

  /**
   * How many dispatch attempts of this entry ended, see {@link #attempts}
   *
   * @return The number of ended attempts
   */
  public int getAttempts() {

    return attempts;

  }

  /**
   * When the next dispatch attempt may start
   *
   * @return The moment the entry becomes due again
   */
  public Instant getNextAttemptAt() {

    return nextAttemptAt;

  }

  /**
   * When the dispatch reached the BPMS, which the retention deletes by
   *
   * @return The moment the entry was dispatched, or <code>null</code> while it is open
   */
  public Instant getDoneAt() {

    return doneAt;

  }

  /**
   * Which node is dispatching this entry, see {@link #leasedBy}
   *
   * @return The node holding the entry, or <code>null</code> where nobody holds it
   */
  public String getLeasedBy() {

    return leasedBy;

  }

  /**
   * How long the claim on this entry lasts, see {@link #leasedUntil}
   *
   * @return The moment the lease runs out, or <code>null</code> where nobody holds the entry
   */
  public Instant getLeasedUntil() {

    return leasedUntil;

  }

  /**
   * The entry's own id, which MongoDB keeps unique
   *
   * @param id The entry id
   */
  public void setId(
      final String id) {

    this.id = id;

  }

  /**
   * The workflow module the call belongs to
   *
   * @param workflowModuleId The workflow module id
   */
  public void setWorkflowModuleId(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  /**
   * The BPMN process the call belongs to
   *
   * @param bpmnProcessId The BPMN process id
   */
  public void setBpmnProcessId(
      final String bpmnProcessId) {

    this.bpmnProcessId = bpmnProcessId;

  }

  /**
   * The name of the scheduled {@link io.vanillabp.integration.spi.PhaseOperation}
   *
   * @param operation The operation's name
   */
  public void setOperation(
      final String operation) {

    this.operation = operation;

  }

  /**
   * The workflow aggregate the call belongs to, in its serialized form
   *
   * @param aggregateId The aggregate id as text
   */
  public void setAggregateId(
      final String aggregateId) {

    this.aggregateId = aggregateId;

  }

  /**
   * The id of the BPMS adapter elected at scheduling time, see {@link #adapterId}
   *
   * @param adapterId The adapter id, or <code>null</code>
   */
  public void setAdapterId(
      final String adapterId) {

    this.adapterId = adapterId;

  }

  /**
   * What the operation is called with
   *
   * @param args The arguments of the call
   */
  public void setArgs(
      final Map<String, String> args) {

    this.args = args;

  }

  /**
   * The call's idempotency key, see {@link #idempotencyKey}
   *
   * @param idempotencyKey The idempotency key, or <code>null</code>
   */
  public void setIdempotencyKey(
      final String idempotencyKey) {

    this.idempotencyKey = idempotencyKey;

  }

  /**
   * What the unique index spans, see {@link #dedupKey}
   *
   * @param dedupKey The key the index holds, never <code>null</code>
   */
  public void setDedupKey(
      final String dedupKey) {

    this.dedupKey = dedupKey;

  }

  /**
   * Where the entry stands: {@link #STATUS_OPEN}, {@link #STATUS_DONE} or {@link
   * #STATUS_BLOCKED}
   *
   * @param status The status
   */
  public void setStatus(
      final String status) {

    this.status = status;

  }

  /**
   * When the call was scheduled
   *
   * @param createdAt The moment the entry was written
   */
  public void setCreatedAt(
      final Instant createdAt) {

    this.createdAt = createdAt;

  }

  /**
   * How many dispatch attempts of this entry ended, see {@link #attempts}
   *
   * @param attempts The number of ended attempts
   */
  public void setAttempts(
      final int attempts) {

    this.attempts = attempts;

  }

  /**
   * When the next dispatch attempt may start
   *
   * @param nextAttemptAt The moment the entry becomes due again
   */
  public void setNextAttemptAt(
      final Instant nextAttemptAt) {

    this.nextAttemptAt = nextAttemptAt;

  }

  /**
   * When the dispatch reached the BPMS, which the retention deletes by
   *
   * @param doneAt The moment the entry was dispatched, or <code>null</code>
   */
  public void setDoneAt(
      final Instant doneAt) {

    this.doneAt = doneAt;

  }

  /**
   * Which node is dispatching this entry, see {@link #leasedBy}
   *
   * @param leasedBy The node holding the entry, or <code>null</code>
   */
  public void setLeasedBy(
      final String leasedBy) {

    this.leasedBy = leasedBy;

  }

  /**
   * How long the claim on this entry lasts, see {@link #leasedUntil}
   *
   * @param leasedUntil The moment the lease runs out, or <code>null</code>
   */
  public void setLeasedUntil(
      final Instant leasedUntil) {

    this.leasedUntil = leasedUntil;

  }
}
