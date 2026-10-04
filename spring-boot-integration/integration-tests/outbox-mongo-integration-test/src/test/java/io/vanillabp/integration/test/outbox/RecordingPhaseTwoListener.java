package io.vanillabp.integration.test.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;

/**
 * Records phase-two invocations of the dummy adapter and optionally fails a
 * configurable number of dispatches (to test retry behavior of the outbox).
 */
public class RecordingPhaseTwoListener implements DummyPhaseTwoListener {

  private final List<Object> invocations = new CopyOnWriteArrayList<>();

  private final AtomicInteger failuresRemaining = new AtomicInteger(0);

  /**
   * How many of the next dispatches are answered the way a BPMS answers whose read model is
   * behind: "not yet, ask again in a while". Zero unless a test asked for it.
   */
  private final AtomicInteger notYetRemaining = new AtomicInteger(0);

  /**
   * The while such an answer names.
   */
  private volatile java.time.Duration notYetWindow = java.time.Duration.ofSeconds(1);

  /**
   * How long every dispatch stays inside the adapter, for a test about a dispatch which takes
   * longer than the lease of its outbox entry. Zero unless a test asked for it, and the test
   * which asked puts it back.
   */
  private final java.util.concurrent.atomic.AtomicLong dispatchTakesMillis = new java.util.concurrent.atomic.AtomicLong(0);

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    invocations.add(workflowAggregateId);
    final var takes = dispatchTakesMillis.get();
    if (takes > 0) {
      try {
        Thread.sleep(takes);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (notYetRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
      throw new io.vanillabp.integration.spi.PhaseTwoRetryLater(
          "the BPMS does not report the workflow yet, for testing purposes", notYetWindow);
    }
    if (failuresRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
      throw new RuntimeException("phase two failed for testing purposes");
    }

  }

  /**
   * Makes every dispatch stay inside the adapter for a while, which is what an operation
   * calling a system of somebody else's looks like from the outbox.
   *
   * @param millis How long one dispatch lasts, zero to return every dispatch at once
   */
  public void eachDispatchTakes(
      final long millis) {

    dispatchTakesMillis.set(millis);

  }

  /**
   * Answers the next dispatches the way a BPMS answers whose read model is behind, which is
   * what a Camunda 8 cluster with a stopped exporter does.
   *
   * @param howMany How many dispatches are answered "not yet"
   * @param window How long each answer asks the outbox to wait
   */
  public void answerNotYet(
      final int howMany,
      final java.time.Duration window) {

    notYetWindow = window;
    notYetRemaining.set(howMany);

  }

  public void failNextDispatches(
      final int numberOfFailures) {

    failuresRemaining.set(numberOfFailures);

  }

  public List<Object> getInvocations() {

    return List.copyOf(invocations);

  }

  public void reset() {

    invocations.clear();
    failuresRemaining.set(0);
    notYetRemaining.set(0);
    dispatchTakesMillis.set(0);

  }

  /**
   * Waits until at least the given number of phase-two invocations were recorded.
   * <p>
   * This says that the ADAPTER was called, not that the operation is over: the listener
   * runs inside the dispatch, and the dispatcher marks the outbox entry DONE - which is
   * what frees its idempotency key - only after the dispatch returned. On return the
   * entry may still be waiting to be marked, so a repetition of the same operation
   * planned right here can be discarded as a duplicate. A test which needs the key to
   * be free has to wait for the ENTRY, in the store.
   *
   * @param numberOfInvocations The number of invocations to wait for
   * @param timeoutMillis How long to wait at most
   * @return All invocations recorded so far
   * @throws AssertionError If the invocations did not happen within the timeout
   */
  public List<Object> awaitInvocations(
      final int numberOfInvocations,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (invocations.size() < numberOfInvocations) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "Expected at least %d phase-two invocation(s) within %dms but got %d!"
                .formatted(numberOfInvocations, timeoutMillis, invocations.size()));
      }
      Thread.sleep(50);
    }
    return getInvocations();

  }

}
