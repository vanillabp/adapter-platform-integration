package io.vanillabp.integration.adapter.migration.processservice;

/**
 * Phase two lost a race against another change of the same workflow, and the BPMS undid
 * everything the operation did. The adapter said so through
 * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService#kindOfPhaseTwoFailure(Throwable)},
 * which answered {@link io.vanillabp.integration.adapter.spi.PhaseTwoFailureKind#COLLISION}.
 * <p>
 * It never leaves the core. The {@link PhaseTwoRouter} repeats the dispatch a few times at
 * once. If that does not help, it hands the failure of the adapter to the store, unwrapped.
 * So a store sees the same failure it saw before collisions were told apart, and counts it
 * as an attempt. See {@code DECISIONS.pending/2172.md}.
 */
public final class PhaseTwoCollision extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Marks a failure which the adapter called a lost race.
   *
   * @param message What lost the race, for the log of a repetition
   * @param cause What the adapter caught
   */
  public PhaseTwoCollision(
      final String message,
      final RuntimeException cause) {

    super(message, cause);

  }

  /**
   * Finds the collision in whatever wrapped it on the way out of the transaction.
   *
   * @param failure What a dispatch threw
   * @return The collision, or <code>null</code> if the failure is none
   */
  static PhaseTwoCollision find(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof PhaseTwoCollision collision) {
        return collision;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return null;

  }

  /**
   * The failure the adapter caught. It is what the store gets when the repetitions did not
   * help.
   *
   * @return The failure of the adapter
   */
  RuntimeException failureOfTheAdapter() {

    return (RuntimeException) getCause();

  }

}
