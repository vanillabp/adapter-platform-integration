package io.vanillabp.integration.outbox.gruelbox;

import java.time.Duration;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import lombok.extern.slf4j.Slf4j;

/**
 * What gruelbox calls once the scheduling transaction committed: it rebuilds the
 * {@link PhaseTwoCall} and routes it through the core's {@link PhaseTwoRouter}.
 * <p>
 * It also does the one thing this store cannot express itself. A dispatch which fails
 * because the BPMS has not made the workflow searchable yet says how long that takes
 * ({@link PhaseTwoRetryLater}, on Camunda 8 the ten seconds of its
 * <code>workflow-visibility-timeout</code>), and the stores VanillaBP wrote give the
 * entry back with exactly that due time. Gruelbox has one backoff for the whole outbox,
 * so an entry given back here would come back after
 * <code>vanillabp.outbox.attempt-frequency</code> - thirty seconds by default, where ten
 * would have done. A message correlated right after the workflow was started is the
 * ordinary case that turns into, and half a minute is a long time for something the
 * cluster is a second away from.
 * <p>
 * So the window is waited out HERE, in slices, trying again after each of them. It holds
 * the dispatching thread of this store, which is the price this store charges for not
 * being able to shorten the wait of a single entry: everything else waiting in it waits
 * with this one. That is accepted deliberately - VanillaBP is not built for high
 * throughput, and an application which cares runs the store VanillaBP writes itself.
 * <p>
 * What is NOT waited out is a workflow which never becomes visible. The window is
 * bounded, and once it is used up the failure travels on: gruelbox counts the attempt
 * and applies its own backoff, and
 * <code>vanillabp.outbox.block-after-attempts</code> of them still block the entry.
 */
@Slf4j
public class GruelboxPhaseTwoDispatchBean implements GruelboxPhaseTwoDispatch {

  /**
   * How long the thread sleeps before it asks again. Short enough that the wait ends
   * roughly when the workflow becomes visible rather than when the window is over.
   */
  private static final Duration SLICE = Duration.ofMillis(500);

  private final PhaseTwoRouter phaseTwoRouter;

  public GruelboxPhaseTwoDispatchBean(
      final PhaseTwoRouter phaseTwoRouter) {

    this.phaseTwoRouter = phaseTwoRouter;

  }

  @Override
  public void dispatch(
      final String operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final String serializedArgs) {

    final var call = PhaseTwoCall
        .forDispatch(
            operation,
            workflowModuleId,
            bpmnProcessId,
            workflowAggregateId,
            adapterId,
            PhaseTwoCall.deserializeArgs(serializedArgs));
    // set by the submitter wrapper on this thread - a retried entry runs the START
    // re-dispatch mitigation
    final var previouslyAttempted = GruelboxRedispatchAwareSubmitter.isPreviouslyAttempted();

    Duration remainingWindow = null;
    while (true) {
      try {
        phaseTwoRouter.dispatch(call, previouslyAttempted);
        return;
      } catch (final RuntimeException e) {
        final var window = PhaseTwoRetryLater.retryAfter(e);
        if (window == null) {
          throw e;
        }
        // the window is the adapter's answer and the same on every round, so the first
        // one is counted down rather than restarted by the next failure
        if (remainingWindow == null) {
          remainingWindow = window;
        }
        if (remainingWindow.isZero() || remainingWindow.isNegative()) {
          log.debug(
              "Gruelbox: the workflow of {} did not become visible within {} - the entry goes back "
                  + "to the outbox and is dispatched again after 'vanillabp.outbox.attempt-frequency'",
              workflowAggregateId,
              window);
          throw e;
        }
        final var slice = remainingWindow.compareTo(SLICE) < 0
            ? remainingWindow
            : SLICE;
        remainingWindow = remainingWindow.minus(slice);
        sleep(slice, e);
      }
    }

  }

  /**
   * Waits one slice of the window. An interrupted wait ends the waiting rather than
   * swallowing the interruption: the entry goes back to the outbox, which is what an
   * application shutting down needs.
   *
   * @param slice How long to wait
   * @param failure What the dispatch failed with, thrown when the wait is interrupted
   */
  private static void sleep(
      final Duration slice,
      final RuntimeException failure) {

    try {
      Thread.sleep(slice.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure;
    }

  }

}
