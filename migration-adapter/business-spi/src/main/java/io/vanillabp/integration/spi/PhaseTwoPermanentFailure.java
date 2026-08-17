package io.vanillabp.integration.spi;

/**
 * A phase-two operation which failed for a reason repeating cannot fix - the adapter
 * said so (story 63).
 * <p>
 * The outbox repeats a failed dispatch until the entry is blocked, which is what makes
 * an operation losing a concurrency conflict survivable. A failure the BPMS will
 * answer the same way every time gains nothing from that: the entry is blocked right
 * away, so operations see it while the log still says why instead of after the
 * configured attempts.
 * <p>
 * Stores recognise it through {@link #isPermanent(Throwable)}, which walks the causes -
 * the transaction a store dispatches in may wrap what was thrown.
 */
public class PhaseTwoPermanentFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PhaseTwoPermanentFailure(
      final String message,
      final Throwable cause) {

    super(message, cause);

  }

  /**
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
