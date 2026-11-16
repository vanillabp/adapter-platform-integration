package io.vanillabp.integration.spi;

/**
 * A phase-two operation which the BPMS refused because it is busy right now. The BPMS did
 * nothing of the operation, and it will most probably take it in a while. The core throws it
 * where the adapter calls a failure busy, and it wraps what the adapter threw.
 * <p>
 * A store recognises it through {@link #isBusy(Throwable)}, which walks the causes the way
 * {@link PhaseTwoPermanentFailure#isPermanent(Throwable)} does. It answers it like this:
 * <ul>
 * <li><b>It is not an attempt.</b> The attempt counter of the entry stays as it was, so
 * <code>vanillabp.outbox.block-after-attempts</code> never blocks an entry only because its
 * BPMS was full. There is no other limit either. The BPMS decides how long this lasts, and
 * the WARN line of each answer shows that it lasts.</li>
 * <li><b>A fixed wait.</b> The entry is due again after
 * <code>vanillabp.outbox.attempt-frequency</code>, spread a little so that the entries
 * which were refused together do not come back together. The growing backoff of a failed
 * dispatch does not apply. It exists for a BPMS which is away, and an overloaded BPMS is
 * there.</li>
 * <li><b>A WARN line each time.</b> It names the operation, the workflow and the adapter
 * (in the message of this failure), and when the entry comes back. That line is the place
 * where an overloaded BPMS becomes visible in operation. So it is never written on a lower
 * level.</li>
 * </ul>
 * Every store VanillaBP ships does this: the relational store of the core, the two MongoDB
 * stores, and the gruelbox store which a Spring Boot application with JPA gets from
 * <code>io.vanillabp:gruelbox-phase-two-outbox</code>. A store which does not know this type
 * counts the failure as an ordinary attempt. That is what every store did before busy
 * answers were told apart, and nothing is lost by it.
 */
public class PhaseTwoBpmsBusy extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Marks a failure which the adapter called busy.
   *
   * @param message Which operation was refused, of which workflow and by which adapter. A
   *        store writes it into its WARN line
   * @param cause What the adapter threw
   */
  public PhaseTwoBpmsBusy(
      final String message,
      final Throwable cause) {

    super(message, cause);

  }

  /**
   * Whether a dispatch failed because its BPMS is busy. A store asks this way, because the
   * failure may arrive wrapped by the transaction it ran in.
   *
   * @param failure The failure a dispatch ended with
   * @return Whether the failure or one of its causes is a {@link PhaseTwoBpmsBusy}
   */
  public static boolean isBusy(
      final Throwable failure) {

    return find(failure) != null;

  }

  /**
   * The message of the busy answer inside whatever wrapped it. A store writes it into its
   * WARN line, because it names the adapter, which the entry does not always know.
   *
   * @param failure The failure a dispatch ended with
   * @return The message, or <code>null</code> if the failure is not a busy answer
   */
  public static String messageOf(
      final Throwable failure) {

    final var busy = find(failure);
    return busy == null
        ? null
        : busy.getMessage();

  }

  private static PhaseTwoBpmsBusy find(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof PhaseTwoBpmsBusy busy) {
        return busy;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return null;

  }

}
