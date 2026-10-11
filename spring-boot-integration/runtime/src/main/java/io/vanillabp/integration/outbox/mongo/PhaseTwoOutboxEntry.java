package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;

/**
 * A single entry of the MongoDB-based phase-two outbox, stored in the configured
 * collection (<code>vanillabp.outbox.mongo.collection</code>). The entry stores the fields
 * of a {@link io.vanillabp.integration.spi.PhaseTwoCall}. The workflow aggregate's ID is
 * kept in its serialized (String) form. The core's router converts it back to the
 * aggregate's ID type at dispatch time.
 * <p>
 * The {@link #idempotencyKey} carries the call's idempotency key, if there is one. It
 * stays readable for whoever looks at the collection during support. What deduplicates
 * is {@link #dedupKey}, which an index on the collection keeps unique. While the entry
 * waits for its dispatch, it holds that same key. From the moment the entry was
 * dispatched, it holds the entry's own id. So the uniqueness spans the operations which
 * are still planned. That is the deduplication of the outbox contract, at the storage
 * level. The {@link #status} goes from {@link #STATUS_OPEN} to {@link #STATUS_DONE}
 * (successful dispatch, deleted in the background after the configured retention) or to
 * {@link #STATUS_BLOCKED} (too many failed attempts, somebody has to clean up by hand).
 * {@link #lastFailure} says why the last attempt did not get through. So a blocked entry
 * names its reason without the log.
 */
public class PhaseTwoOutboxEntry {

  /**
   * An entry which is still waiting for its dispatch. Its {@link #dedupKey} is the
   * idempotency key. So while this entry stands, a second call which plans the same
   * operation is discarded.
   */
  public static final String STATUS_OPEN = "OPEN";

  /**
   * An entry which reached the BPMS. It is kept for <code>vanillabp.outbox.retention</code>
   * and deleted afterwards, so support can still read what was dispatched. By then its
   * {@link #dedupKey} is its own id. The deduplication ends where the dispatch is over
   * (see decision 22 in the repository's DECISIONS.md).
   */
  public static final String STATUS_DONE = "DONE";

  /**
   * An entry which used up <code>vanillabp.outbox.block-after-attempts</code> attempts,
   * which waited longer than <code>vanillabp.outbox.wait-for-visibility-at-most</code> for
   * its BPMS, or whose failure the adapter called permanent. It waits for a person. No poll
   * takes it again, and no retention deletes it. That is why it is the one status somebody
   * has to clean up by hand.
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
   * The ID of the BPMS adapter elected at scheduling time. It may be <code>null</code> for
   * future probing operations.
   */
  private String adapterId;

  /**
   * What the operation is called with, as the scheduling side handed it over.
   */
  private Map<String, String> args;

  /**
   * The call's idempotency key, <code>null</code> if the operation must not be
   * deduplicated. It only describes the entry. It is never used to look an entry up.
   */
  private String idempotencyKey;

  /**
   * What the unique index spans. While the entry waits for its dispatch, it is the
   * idempotency key. Once the entry was dispatched, or where the operation must not be
   * deduplicated at all, it is the entry's own {@link #id}. It is never <code>null</code>.
   * So a database which treats two nulls as equal cannot refuse the second entry without a
   * key.
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
   * How many dispatch attempts of this entry ENDED, whatever they ended with. It is not how
   * often the entry was claimed. An entry which is being dispatched right now has counted
   * nothing yet. That keeps <code>vanillabp.outbox.block-after-attempts</code> from
   * blocking an entry for being slow.
   */
  private int attempts;

  /**
   * When the next dispatch attempt may start. A poll passes over an entry which is not
   * due yet.
   */
  private Instant nextAttemptAt;

  /**
   * When the dispatch reached the BPMS. The retention deletes by it. <code>null</code>
   * while the entry is still open.
   */
  private Instant doneAt;

  /**
   * Which node is dispatching this entry, <code>null</code> for an entry nobody holds. A
   * renewal of the lease matches on it. So a node whose lease ran out and was taken over
   * renews nothing.
   */
  private String leasedBy;

  /**
   * How long the claim on this entry lasts. No poll takes an entry whose lease has not run
   * out. A running dispatch pushes the moment along for as long as it runs (see
   * {@link io.vanillabp.integration.adapter.migration.outbox.DispatchLease}).
   * <code>null</code> for an entry nobody holds.
   */
  private Instant leasedUntil;

  /**
   * Why the last attempt of this entry did not get through. <code>null</code> where it went
   * through, or where no attempt ended yet. It is written together with whatever ended the
   * attempt: the next due time or the block.
   * {@link io.vanillabp.integration.adapter.migration.outbox.LastFailure} builds it, so it
   * reads the same as the column of the JDBC table.
   */
  private String lastFailure;

  /**
   * What Spring Data starts from when it reads an entry of the collection. It builds the
   * empty entry and fills the fields afterwards. The outbox which writes an entry uses the
   * constructor which takes every field.
   */
  public PhaseTwoOutboxEntry() {
  }

  /**
   * What the outbox uses to write an entry. Every field is known at that moment.
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
   * @param lastFailure Why the last attempt did not get through, or <code>null</code>
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
      final Instant leasedUntil,
      final String lastFailure) {

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
    this.lastFailure = lastFailure;

  }

  /**
   * Why the last attempt of this entry did not get through, see {@link #lastFailure}
   *
   * @return The reason, or <code>null</code> where the last attempt went through or none ended yet
   */
  public String getLastFailure() {

    return lastFailure;

  }

  /**
   * Why the last attempt of this entry did not get through, see {@link #lastFailure}
   *
   * @param lastFailure The reason, or <code>null</code>
   */
  public void setLastFailure(
      final String lastFailure) {

    this.lastFailure = lastFailure;

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
