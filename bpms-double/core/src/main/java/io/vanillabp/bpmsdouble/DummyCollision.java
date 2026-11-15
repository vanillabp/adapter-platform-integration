package io.vanillabp.bpmsdouble;

/**
 * A phase-two failure this adapter reports as a collision: the operation lost a race against
 * another change of the same workflow, and nothing of it stayed behind. The core repeats
 * such an operation at once, a few times, before the outbox sees the failure.
 * <p>
 * It plays the role of the <code>OptimisticLockingException</code> of Camunda 7, so a test
 * can show the short repetition without a real engine.
 */
public class DummyCollision extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Builds the failure a test throws from a hook of the double, usually from a
   * {@link DummyPhaseTwoListener}.
   *
   * @param message Which change the operation lost against
   */
  public DummyCollision(
      final String message) {

    super(message);

  }

}
