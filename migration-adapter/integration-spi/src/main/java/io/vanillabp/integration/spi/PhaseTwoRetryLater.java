package io.vanillabp.integration.spi;

import java.time.Duration;

/**
 * A phase-two operation which cannot run yet, but most probably can in a moment - and
 * says how long that moment is.
 * <p>
 * The one case today is a workflow whose BPMS has not made it searchable yet: the
 * operation is worth repeating, and the time it needs is the visibility window of the
 * adapter holding it. A store dispatches an entry on the lane of its workflow aggregate,
 * and waiting for that window there would hold every other entry of that lane, whatever
 * workflow it belongs to. So the entry is given back instead - one update rather than a
 * parked thread.
 * <p>
 * A store recognises it through {@link #retryAfter(Throwable)}, which walks the causes
 * the way {@link PhaseTwoPermanentFailure#isPermanent(Throwable)} does, and uses the
 * duration in place of its configured backoff for THIS attempt - the growing backoff of
 * a failed dispatch never stretches this window, because the two are written by
 * different branches of the same failure handling. It changes nothing
 * else: the attempt is counted like any other, so an entry coming back again and again
 * is blocked after <code>vanillabp.outbox.block-after-attempts</code> attempts, which is
 * what stops a workflow which never becomes visible.
 * <p>
 * <b>What this promises an adapter.</b> Every store VanillaBP ships makes the entry due
 * after the window, and none of them shortens it or stretches it: the relational store of
 * the core, the two MongoDB stores and the gruelbox store a Spring Boot application with JPA
 * may opt into. A window longer than <code>vanillabp.outbox.attempt-frequency</code> is
 * therefore waited out, and a window shorter than it is not waited past, so an adapter
 * naming thirty seconds gets the same answer whichever store the application chose
 * (decision 93 of <code>adapter-platform-integration</code>). What a store adds on top is
 * the time it takes to pick a due entry up, which is one poll of
 * <code>vanillabp.outbox.poll-interval</code>.
 * <p>
 * The price of that promise is the adapter's to pay. The entry sits for the window it named
 * and for one of its attempts, so a window of ten seconds for a read model which is a second
 * behind costs nine seconds per call. Name what your BPMS really needs.
 */
public class PhaseTwoRetryLater extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * How long the store should wait before it dispatches the entry again. It is the window
   * the adapter asked for and not a backoff a store computed, which is why a store may use
   * it in place of its own.
   */
  private final Duration retryAfter;

  /**
   * Says that the operation is worth repeating, and how long the store should wait before
   * it does.
   *
   * @param message Why the operation cannot run yet, and what to look at if it stays that
   *        way
   * @param retryAfter How long to wait before the next attempt
   */
  public PhaseTwoRetryLater(
      final String message,
      final Duration retryAfter) {

    super(message);
    this.retryAfter = retryAfter;

  }

  /**
   * How long this failure asks the store to wait.
   *
   * @return How long to wait before the entry is dispatched again
   */
  public Duration getRetryAfter() {

    return retryAfter;

  }

  /**
   * Reads the waiting time out of whatever wrapped the failure, which is how a store asks.
   *
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
