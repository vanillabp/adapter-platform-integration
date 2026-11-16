package io.vanillabp.bpmsdouble;

/**
 * A phase-two failure this adapter reports as busy: the BPMS refused the operation because it
 * is overloaded right now, and it did nothing of it. The outbox tries such an operation again
 * after its attempt frequency, and that uses no attempt of the entry.
 * <p>
 * It plays the role of the backpressure of Camunda 8 (<code>RESOURCE_EXHAUSTED</code>), so a
 * test can show what a busy BPMS costs an entry without a real cluster.
 */
public class DummyBusy extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Builds the failure a test throws from a hook of the double, usually from a
   * {@link DummyPhaseTwoListener}.
   *
   * @param message Why the BPMS refused the operation
   */
  public DummyBusy(
      final String message) {

    super(message);

  }

}
