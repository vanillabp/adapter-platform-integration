package io.vanillabp.integration.spi;

/**
 * A phase-two operation which failed for a reason repeating cannot fix - the adapter
 * said so.
 * <p>
 * The outbox repeats a failed dispatch until the entry is blocked, which is what makes
 * an operation losing a concurrency conflict survivable. A failure the BPMS will
 * answer the same way every time gains nothing from that: the entry is blocked right
 * away, so operations see it while the log still says why instead of after the
 * configured attempts.
 * <p>
 * Stores recognise it through {@link #isPermanent(Throwable)}, which walks the causes -
 * the transaction a store dispatches in may wrap what was thrown.
 * <p>
 * Who decides that a failure will never succeed, and why the classification errs towards
 * repeatable, is decision 12 in the repository's DECISIONS.md.
 */
public class PhaseTwoPermanentFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Says that repeating this operation cannot help, so the outbox entry is blocked right
   * away instead of after the configured attempts.
   *
   * @param message What an operator has to change - it is what the log and the blocked
   *        entry say about this failure
   * @param cause What the adapter caught
   */
  public PhaseTwoPermanentFailure(
      final String message,
      final Throwable cause) {

    super(message, cause);

  }

  /**
   * Whether a failure says that repeating cannot help. It looks through the causes, so a
   * failure wrapped on its way out is recognised as well.
   *
   * @param failure The failure a dispatch ended with
   * @return Whether repeating the operation cannot help
   */
  public static boolean isPermanent(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof PhaseTwoPermanentFailure) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return false;

  }

}
