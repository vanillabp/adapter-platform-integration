package io.vanillabp.integration.outbox.gruelbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this store does with a workflow its BPMS has not made searchable yet.
 * <p>
 * Gruelbox has one backoff for the whole outbox, so an entry given back would return
 * after <code>attempt-frequency</code> - thirty seconds by default, where the ten of a
 * Camunda 8 cluster would have done. The dispatch bean therefore waits the window out
 * instead of giving the entry back, and these tests hold both ends of that: the wait
 * ends as soon as the workflow is there, and it ends at the window whatever happens.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxWaitsOutTheVisibilityWindowTest {

  private static final String ARGS = null;

  private static PhaseTwoRetryLater notVisibleYet() {

    return new PhaseTwoRetryLater("not searchable yet", Duration.ofSeconds(2));

  }

  @Test
  @DisplayName("A workflow which becomes visible is dispatched without going back to the outbox")
  public void theWaitEndsWhenTheWorkflowIsThere() {

    final var attempts = new AtomicInteger();
    final var router = mock(PhaseTwoRouter.class);
    doAnswer(invocation -> {
      if (attempts.incrementAndGet() < 3) {
        throw notVisibleYet();
      }
      return null;
    })
        .when(router)
        .dispatch(any(), anyBoolean());

    final var startedAt = System.nanoTime();
    new GruelboxPhaseTwoDispatchBean(router)
        .dispatch("CORRELATE_MESSAGE", "loan-approval", "loan_approval", "4711", null, ARGS);
    final var waited = Duration.ofNanos(System.nanoTime() - startedAt);

    assertEquals(3, attempts.get(), "the dispatch is repeated on the spot until it works");
    assertTrue(
        waited.compareTo(Duration.ofSeconds(2)) < 0,
        "the wait ends with the workflow, not with the window: "
            + waited);

  }

  @Test
  @DisplayName("A workflow which never becomes visible ends up back in the outbox")
  public void theWaitEndsWithTheWindow() {

    final var attempts = new AtomicInteger();
    final var router = mock(PhaseTwoRouter.class);
    doAnswer(invocation -> {
      attempts.incrementAndGet();
      throw notVisibleYet();
    })
        .when(router)
        .dispatch(any(), anyBoolean());

    final var startedAt = System.nanoTime();
    assertThrows(
        PhaseTwoRetryLater.class,
        () -> new GruelboxPhaseTwoDispatchBean(router)
            .dispatch("CORRELATE_MESSAGE", "loan-approval", "loan_approval", "4711", null, ARGS),
        "once the window is used up the entry goes back, so gruelbox counts the attempt");
    final var waited = Duration.ofNanos(System.nanoTime() - startedAt);

    assertTrue(attempts.get() > 1, "the window is asked in slices, not once: "
        + attempts.get());
    assertTrue(
        waited.compareTo(Duration.ofSeconds(10)) < 0,
        "the wait is bounded by the window the adapter named: "
            + waited);

  }

  @Test
  @DisplayName("Any other failure travels on untouched")
  public void anOrdinaryFailureIsNotWaitedOut() {

    final var attempts = new AtomicInteger();
    final var router = mock(PhaseTwoRouter.class);
    doAnswer(invocation -> {
      attempts.incrementAndGet();
      throw new IllegalStateException("the BPMS refused this");
    })
        .when(router)
        .dispatch(any(), anyBoolean());

    assertThrows(
        IllegalStateException.class,
        () -> new GruelboxPhaseTwoDispatchBean(router)
            .dispatch("CORRELATE_MESSAGE", "loan-approval", "loan_approval", "4711", null, ARGS));

    assertEquals(1, attempts.get(), "a failure which says nothing about a window is not repeated here");

  }

}
