package io.vanillabp.integration.test.upgradecost;

import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;

/**
 * Stands where the BPMS would stand and writes down which thread brought the call. That is
 * the number this story is about: version 1 progressed a workflow in a thread which was
 * already there, and version 2 brings threads of its own.
 * <p>
 * It can also hold every call it gets until a given number of them are inside at the same
 * time. Holding is what proves the width: if fewer threads dispatch than the barrier waits
 * for, nobody arrives and the wait runs out, which is a failed measurement and not a slow
 * one.
 */
public class WhereTheDispatchRan implements DummyPhaseTwoListener {

  private final List<String> threads = new CopyOnWriteArrayList<>();

  private volatile CyclicBarrier holdUntilTheyAreAllHere;

  private volatile long holdAtMostMillis;

  /**
   * Forgets what was seen so far and stops holding anything.
   */
  public void startWatching() {

    threads.clear();
    holdUntilTheyAreAllHere = null;

  }

  /**
   * Holds every call until that many of them are inside at the same time.
   *
   * @param many How many calls have to meet
   * @param atMostMillis How long one of them waits for the others before it gives up
   */
  public void holdEveryCallUntilThereAre(
      final int many,
      final long atMostMillis) {

    holdAtMostMillis = atMostMillis;
    holdUntilTheyAreAllHere = new CyclicBarrier(many);

  }

  /**
   * The threads which brought a call, one entry per call, in the order they arrived.
   *
   * @return The thread names
   */
  public List<String> threadsWhichBroughtACall() {

    return List.copyOf(threads);

  }

  /**
   * How many different threads brought a call.
   *
   * @return The names without repetition
   */
  public Set<String> differentThreads() {

    return Set.copyOf(threads);

  }

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    threads.add(Thread.currentThread().getName());
    final var barrier = holdUntilTheyAreAllHere;
    if (barrier == null) {
      return;
    }
    try {
      barrier.await(holdAtMostMillis, TimeUnit.MILLISECONDS);
    } catch (final TimeoutException e) {
      // the dispatch is narrower than the barrier waits for; the test reads that off the
      // threads it collected rather than off an exception thrown in somebody else's thread
      barrier.reset();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final BrokenBarrierException e) {
      // another call gave up first, so this one is free too
    }

  }

}
