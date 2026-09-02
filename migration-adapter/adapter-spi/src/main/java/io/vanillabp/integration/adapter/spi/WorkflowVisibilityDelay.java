package io.vanillabp.integration.adapter.spi;

import java.time.Duration;

/**
 * How long a BPMS may need until a workflow it holds becomes findable by its
 * awareness probe, and how often to ask meanwhile.
 * <p>
 * An embedded BPMS answers from the very transaction which created the instance and
 * reports {@link #none()}. A BPMS answering from an eventually consistent read model
 * (Camunda 8 searches its query API, which an exporter feeds) needs a moment: right
 * after the start its probe honestly finds nothing, although the workflow exists and
 * VanillaBP started it seconds ago.
 * <p>
 * The core does the waiting, because eventual consistency is what a migration adapter
 * exists for; the adapter contributes the window, because only it knows its BPMS. The
 * waiting is NOT blanket: it happens where VanillaBP has a reason to believe this
 * adapter holds the workflow (see
 * {@code io.vanillabp.integration.spi.WorkflowAdapterCache}), so a workflow which
 * genuinely does not exist still fails immediately.
 *
 * @param window How long an {@link WorkflowAwareness#UNKNOWN_TO_BPMS} answer may still
 *        turn into {@link WorkflowAwareness#ACTIVE}. {@link Duration#ZERO} switches
 *        the waiting off
 * @param interval How long to wait between two probes; ignored for a zero window
 */
public record WorkflowVisibilityDelay(
                                      Duration window,
                                      Duration interval) {

  private static final WorkflowVisibilityDelay NONE = new WorkflowVisibilityDelay(
      Duration.ZERO, Duration.ZERO);

  /**
   * The answer of a BPMS which is immediately consistent: an unknown workflow stays
   * unknown, however often it is asked.
   *
   * @return The zero delay
   */
  public static WorkflowVisibilityDelay none() {

    return NONE;

  }

  /**
   * @return Whether waiting for a workflow to become visible makes sense at all
   */
  public boolean isWaiting() {

    return (window != null) && !window.isZero() && !window.isNegative();

  }

}
