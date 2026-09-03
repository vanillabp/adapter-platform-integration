package io.vanillabp.integration.spi;

import java.time.Duration;

/**
 * A phase-two operation which cannot run yet, but most probably can in a moment - and
 * says how long that moment is.
 * <p>
 * The one case today is a workflow whose BPMS has not made it searchable yet: the
 * operation is worth repeating, and the time it needs is the visibility window of the
 * adapter holding it. Waiting for that window on the dispatching thread would hold
 * every other entry of the same store, whatever workflow it belongs to, so the entry is
 * given back instead - one update rather than a parked thread.
 * <p>
 * A store recognises it through {@link #retryAfter(Throwable)}, which walks the causes
 * the way {@link PhaseTwoPermanentFailure#isPermanent(Throwable)} does, and uses the
 * duration in place of its configured backoff for THIS attempt - the growing backoff of
 * a failed dispatch never stretches this window, because the two are written by
 * different branches of the same failure handling. It changes nothing
 * else: the attempt is counted like any other, so an entry coming back again and again
 * is blocked after <code>vanillabp.outbox.block-after-attempts</code> attempts, which is
 * what stops a workflow which never becomes visible. A store which cannot express a
 * per-entry due time may ignore the duration and use its own backoff - the operation is
 * then repeated later than it had to be, never sooner.
 */
public class PhaseTwoRetryLater extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Duration retryAfter;

  public PhaseTwoRetryLater(
      final String message,
      final Duration retryAfter) {

    super(message);
    this.retryAfter = retryAfter;

  }

  /**
   * @return How long to wait before the entry is dispatched again
   */
  public Duration getRetryAfter() {

    return retryAfter;

  }

  /**
   * @param failure The failure a dispatch ended with
   * @return How long to wait before the next attempt, or <code>null</code> where the
   *         failure says nothing about it and the store's own backoff applies
   */
  public static Duration retryAfter(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof PhaseTwoRetryLater retryLater) {
        return retryLater.getRetryAfter();
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return null;

  }

}
