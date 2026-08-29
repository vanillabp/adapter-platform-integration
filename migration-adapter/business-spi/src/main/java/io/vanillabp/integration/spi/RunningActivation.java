package io.vanillabp.integration.spi;

/**
 * Which activation of a BPMN element is executing on this thread, for the moment an
 * operation is planned. Opened by the core around everything it delivers to application
 * code and read where a phase-two key is derived
 * ({@link PhaseOperation#CORRELATE_MESSAGE}), so the application passes nothing and
 * an operation started from a REST endpoint keeps the key it always had.
 * <p>
 * <strong>What an activation identity is.</strong> The value the delivering adapter
 * reports for the element instance which is running: distinct for every activation of
 * an element, and deliberately NOT distinct between redeliveries of one activation.
 * That is the difference to the identity of a DELIVERY
 * ({@code TaskInvocationContext#getDeliveryId()}), which has to stay the SAME across
 * redeliveries so a repeated delivery can be answered from its record. The two look
 * alike on the BPMS which answers one value for both, and Camunda 7 shows that they are
 * not the same question: it reports no delivery id at all and still knows which
 * activity instance is executing.
 * <p>
 * <strong>Which invocations open a scope</strong>, and why the third one does not:
 * <ul>
 * <li>a task delivered to a <code>&#64;WorkflowTask</code> method opens one, with what
 * the adapter reports. Three elements of a multi-instance call activity correlate the
 * same message name with the same correlation id for the same aggregate, and without
 * this value their keys are equal and two of the three are discarded (see decision 23
 * in the repository's DECISIONS.md);</li>
 * <li>a workflow the BPMS started opens one, with the id of the started instance
 * ({@code BpmsInitiatedStartContext#getNativeInstanceId()}). One start event firing
 * twice produces two instances and therefore two identities;</li>
 * <li>the end of a workflow opens none, and needs none: a workflow ends once, so two
 * correlations from an ended-workflow handler carrying the same values really are one
 * operation, and deduplicating them is right.</li>
 * </ul>
 * Somebody adding a fourth callback decides which of those three shapes theirs has.
 * <p>
 * <strong>Bound to the thread, and only to it.</strong> The handler runs on the thread
 * the core invoked it on, on both platforms, so a {@link ThreadLocal} spans exactly the
 * invocation. It is deliberately not an {@code InheritableThreadLocal}: a pool thread
 * created during one activation would carry that activation into every unrelated piece
 * of work it runs afterwards, which is worse than reporting nothing. A handler which
 * hands work to a thread of its own and correlates from there therefore sees no
 * activation, and its key falls back to the shape every VanillaBP application had
 * before - absent rather than failing, so an application which works today keeps
 * working after the upgrade.
 * <p>
 * The scope restores the previous value instead of clearing it, because an embedded
 * engine can invoke a second handler within the first one's execution. Use it as a
 * resource:
 *
 * <pre>
 * try (var activation = RunningActivation.of(context.getActivationId())) {
 *   ...
 * }
 * </pre>
 */
public final class RunningActivation implements AutoCloseable {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private final String previous;

  private RunningActivation(
      final String activationId) {

    this.previous = CURRENT.get();
    set(activationId);

  }

  /**
   * Opens a scope reporting the given activation for as long as it is open.
   *
   * @param activationId What the adapter calls the running element instance, or
   *          <code>null</code> where it cannot say - a scope opened with
   *          <code>null</code> hides an outer one instead of leaking it into work
   *          which does not belong to it
   * @return The scope, to be closed when the invocation is done
   */
  public static RunningActivation of(
      final String activationId) {

    return new RunningActivation(activationId);

  }

  /**
   * The activation executing on this thread.
   *
   * @return The activation's identity, or <code>null</code> outside any invocation and
   *         wherever the delivering adapter does not report one
   */
  public static String current() {

    return CURRENT.get();

  }

  private static void set(
      final String activationId) {

    if (activationId == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(activationId);
    }

  }

  @Override
  public void close() {

    set(previous);

  }

}
