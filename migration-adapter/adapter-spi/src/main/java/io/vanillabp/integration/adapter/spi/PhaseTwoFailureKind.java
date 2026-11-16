package io.vanillabp.integration.adapter.spi;

/**
 * What kind of failure a phase-two operation ended with, as its adapter sees it. The adapter
 * answers through {@link MigratableProcessService#kindOfPhaseTwoFailure(Throwable)}, and the
 * core decides what the answer means for the outbox entry.
 * <p>
 * The kind only matters for a failure which
 * {@link MigratableProcessService#isPhaseTwoFailureRepeatable(Throwable)} calls repeatable. A
 * failure which repeating cannot fix blocks the entry, whatever its kind.
 */
public enum PhaseTwoFailureKind {

  /**
   * The operation lost a race against another change of the same workflow, and the BPMS undid
   * everything it did. The core repeats it at once, a few times with short pauses, before the
   * outbox counts an attempt. On Camunda 7 that is the <code>OptimisticLockingException</code>.
   */
  COLLISION,

  /**
   * The BPMS refused the operation because it is busy right now, and it did nothing of it. The
   * outbox tries it again after <code>vanillabp.outbox.attempt-frequency</code>, and that uses
   * no attempt of the entry. Each such answer is logged as a WARN, because it is the one place
   * where an overloaded BPMS shows up in the log. On Camunda 8 that is the backpressure of the
   * cluster: <code>RESOURCE_EXHAUSTED</code> on gRPC, and HTTP 503 with that title on REST.
   */
  BUSY,

  /**
   * Any other failure. The outbox counts an attempt and tries again after its growing backoff.
   * A BPMS which cannot be reached is such a failure: it is an outage, not a busy BPMS.
   */
  OTHER

}
