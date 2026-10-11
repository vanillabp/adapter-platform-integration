package io.vanillabp.bpmsdouble;

/**
 * A phase-two failure this adapter reports as permanent. The store blocks the outbox
 * entry. It does not retry it until the configured attempts are used up.
 */
public class DummyPermanentFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Builds the failure a test throws from a hook of the double, usually from a
   * {@link DummyPhaseTwoListener}, to show that such an entry is blocked at the first
   * attempt.
   *
   * @param message Why the operation can never succeed
   */
  public DummyPermanentFailure(
      final String message) {

    super(message);

  }

}
