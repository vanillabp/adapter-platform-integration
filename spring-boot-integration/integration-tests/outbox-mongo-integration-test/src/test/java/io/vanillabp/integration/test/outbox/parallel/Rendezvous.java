package io.vanillabp.integration.test.outbox.parallel;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Holds every iteration of the parallel multi-instance test until all of them loaded the
 * aggregate. Only then does any of them return and let VanillaBP save. So all iterations
 * work on the same state of the document, as they do on a busy BPMS, and the test does not
 * depend on a lucky race.
 */
@Component
public class Rendezvous {

  private volatile CyclicBarrier barrier;

  /**
   * Prepares the rendezvous for the next test.
   *
   * @param iterations How many iterations have to arrive before all of them go on
   */
  public void expect(
      final int iterations) {

    barrier = new CyclicBarrier(iterations);

  }

  /**
   * Waits until every iteration arrived. One minute is a guard against a test which hangs.
   * It is never what the test measures.
   */
  public void arrive() {

    try {
      barrier.await(1, TimeUnit.MINUTES);
    } catch (final Exception e) {
      throw new IllegalStateException("Not every iteration arrived", e);
    }

  }

}
