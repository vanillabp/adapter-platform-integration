package io.vanillabp.integration.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records phase-two invocations of the dummy adapter and optionally fails a
 * configurable number of dispatches (to test retry behavior of the outbox).
 */
@ApplicationScoped
public class RecordingPhaseTwoListener implements DummyPhaseTwoListener {

  private final List<Object> invocations = new CopyOnWriteArrayList<>();

  private final AtomicInteger failuresRemaining = new AtomicInteger(0);

  /**
   * How long every dispatch stays inside the adapter, for a test about a dispatch which takes
   * longer than the lease of its outbox entry. Zero unless a test asked for it, and the test
   * which asked puts it back.
   */
  private final java.util.concurrent.atomic.AtomicLong dispatchTakesMillis = new java.util.concurrent.atomic.AtomicLong(0);

  /**
   * The next dispatch to be stopped in the middle, taken by the dispatch which finds it. A
   * test which needs something to happen to an outbox entry while its dispatch is under way
   * asks for one and lets it go afterwards.
   */
  private final java.util.concurrent.atomic.AtomicReference<HeldDispatch> holdTheNextDispatch = new java.util.concurrent.atomic.AtomicReference<>();

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    invocations.add(workflowAggregateId);
    final var hold = holdTheNextDispatch.getAndSet(null);
    if (hold != null) {
      hold.waitUntilTheTestLetsGo();
    }
    final var takes = dispatchTakesMillis.get();
    if (takes > 0) {
      try {
        Thread.sleep(takes);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
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

  public void failNextDispatches(
      final int numberOfFailures) {

    failuresRemaining.set(numberOfFailures);

  }

  /**
   * Stops the dispatch which comes next until the test lets it go. The test holds the
   * returned handle and has to end it, even where it failed in between: the thread waiting on
   * it is the one the outbox polls with.
   *
   * @return The handle the test ends the held dispatch with
   */
  public HeldDispatch holdTheNextDispatch() {

    final var hold = new HeldDispatch();
    holdTheNextDispatch.set(hold);
    return hold;

  }

  /**
   * One dispatch stopped inside the adapter, which is where a test can work on the outbox
   * entry of an operation that is under way.
   */
  public static final class HeldDispatch {

    /**
     * How long a held dispatch waits at most. It is a guard against a test which forgot to
     * end its hold, not a distance anything is timed by.
     */
    private static final long UNTIL_THE_TEST_LETS_GO = 60_000;

    private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);

    private volatile RuntimeException endsWith;

    private void waitUntilTheTestLetsGo() {

      try {
        released.await(UNTIL_THE_TEST_LETS_GO, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (endsWith != null) {
        throw endsWith;
      }

    }

    /**
     * Lets the held dispatch end the way the test wants it to end.
     *
     * @param failure What the dispatch throws, <code>null</code> where it succeeds
     */
    public void endWith(
        final RuntimeException failure) {

      endsWith = failure;
      released.countDown();

    }

    /**
     * Lets the held dispatch end as a dispatch which got through.
     */
    public void end() {

      endWith(null);

    }

  }

  public List<Object> getInvocations() {

    return List.copyOf(invocations);

  }

  public void reset() {

    invocations.clear();
    failuresRemaining.set(0);
    dispatchTakesMillis.set(0);
    final var neverTaken = holdTheNextDispatch.getAndSet(null);
    if (neverTaken != null) {
      neverTaken.end();
    }

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
