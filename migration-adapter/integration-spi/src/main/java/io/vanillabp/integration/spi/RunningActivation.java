package io.vanillabp.integration.spi;

/**
 * Which activation of a BPMN element runs on this thread, at the moment an operation is
 * planned. The core opens it around everything it delivers to application code. It is
 * read where a phase-two key is derived ({@link PhaseOperation#CORRELATE_MESSAGE}). So
 * the application passes nothing, and an operation started from a REST endpoint keeps
 * the key it always had.
 * <p>
 * <strong>What an activation identity is.</strong> The value the delivering adapter
 * reports for the running element instance. It is different for every activation of an
 * element, and on purpose NOT different between redeliveries of one activation. That is
 * the difference to the identity of a DELIVERY
 * ({@code TaskInvocationContext#getDeliveryId()}). That one has to stay the SAME across
 * redeliveries, so a repeated delivery can be answered from its record. The two look
 * alike on a BPMS which answers one value for both. Camunda 7 shows that they are not
 * the same question: it reports no delivery id at all, and still knows which activity
 * instance runs.
 * <p>
 * <strong>Which invocations open a scope</strong>, and why the third one does not:
 * <ul>
 * <li>a task delivered to a <code>&#64;WorkflowTask</code> method opens one, with what
 * the adapter reports. Three elements of a multi-instance call activity correlate the
 * same message name with the same correlation id for the same aggregate. Without this
 * value their keys are equal, and two of the three are discarded (see decision 23
 * in the repository's DECISIONS.md);</li>
 * <li>a workflow the BPMS started opens one, with the id of the started instance
 * ({@code BpmsInitiatedStartContext#getNativeInstanceId()}). If one start event fires
 * twice, there are two instances, and so two identities;</li>
 * <li>the end of a workflow opens none, and needs none. A workflow ends once. So two
 * correlations with the same values from a handler of an ended workflow really are one
 * operation, and it is right to deduplicate them.</li>
 * </ul>
 * Whoever adds a fourth callback decides which of those three shapes it has.
 * <p>
 * <strong>Bound to the thread, and only to it.</strong> On both platforms, the handler
 * runs on the thread the core invoked it on. So a {@link ThreadLocal} covers exactly the
 * invocation. It is on purpose not an {@code InheritableThreadLocal}. A pool thread
 * created during one activation would carry that activation into every unrelated piece
 * of work it runs afterwards, and that is worse than reporting nothing. So a handler
 * which hands work to a thread of its own and correlates from there sees no activation.
 * Its key falls back to the shape every VanillaBP application had before. It is absent
 * and does not fail, so an application which works today keeps working after the
 * upgrade.
 * <p>
 * The scope restores the previous value instead of clearing it, because an embedded
 * engine can invoke a second handler within the execution of the first one. Use it as a
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
   *          <code>null</code> if it cannot say. A scope opened with <code>null</code>
   *          hides an outer one, instead of leaking it into work which does not belong
   *          to it
   * @return The scope, to be closed when the invocation is done
   */
  public static RunningActivation of(
      final String activationId) {

    return new RunningActivation(activationId);

  }

  /**
   * The activation which runs on this thread.
   *
   * @return The identity of the activation, or <code>null</code> outside any invocation
   *         and wherever the delivering adapter does not report one
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
