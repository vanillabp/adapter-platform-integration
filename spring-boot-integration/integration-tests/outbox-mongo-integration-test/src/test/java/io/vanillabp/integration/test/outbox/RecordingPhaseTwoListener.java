package io.vanillabp.integration.test.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;

/**
 * Records the phase-two calls of the dummy adapter. It can also fail a given number of
 * dispatches, to test how the outbox retries.
 */
public class RecordingPhaseTwoListener implements DummyPhaseTwoListener {

  private final List<Object> invocations = new CopyOnWriteArrayList<>();

  private final AtomicInteger failuresRemaining = new AtomicInteger(0);

  /**
   * How many of the next dispatches get the answer of a BPMS whose read model is behind:
   * "not yet, ask again in a while". Zero unless a test asked for it.
   */
  private final AtomicInteger notYetRemaining = new AtomicInteger(0);

  /**
   * How long "a while" is in such an answer.
   */
  private volatile java.time.Duration notYetWindow = java.time.Duration.ofSeconds(1);

  /**
   * How long every dispatch stays inside the adapter. It is for a test about a dispatch
   * which takes longer than the lease of its outbox entry. Zero unless a test asked for it,
   * and that test puts it back.
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
   * Makes every dispatch stay inside the adapter for a while. That is how an operation
   * which calls somebody else's system looks from the outbox.
   *
   * @param millis How long one dispatch lasts, zero to return every dispatch at once
   */
  public void eachDispatchTakes(
      final long millis) {

    dispatchTakesMillis.set(millis);

  }

  /**
   * Answers the next dispatches like a BPMS whose read model is behind. A Camunda 8 cluster
   * with a stopped exporter answers like this.
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
   * Waits until at least the given number of phase-two calls were recorded.
   * <p>
   * This says that the ADAPTER was called, not that the operation is over. The listener
   * runs inside the dispatch. The dispatcher marks the outbox entry DONE only after the
   * dispatch returned, and only that frees its idempotency key. When this method returns,
   * the entry may not be marked yet. So the same operation, planned again right here, may
   * be thrown away as a duplicate. A test which needs a free key must wait for the ENTRY in
   * the store.
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
