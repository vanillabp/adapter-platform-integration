package io.vanillabp.integration.adapter.spi;

import java.time.Duration;

/**
 * How long a BPMS may need until its awareness probe can find a workflow it holds, and
 * how often to ask in the meantime.
 * <p>
 * An embedded BPMS answers from the same transaction which created the instance, and
 * reports {@link #none()}. A BPMS which answers from an eventually consistent read model
 * needs a moment. Camunda 8, for example, searches its query API, which an exporter
 * feeds. Right after the start its probe honestly finds nothing, although the workflow
 * exists and VanillaBP started it seconds ago.
 * <p>
 * The core does the waiting, because the migration adapter exists to deal with eventual
 * consistency. The adapter gives the window, because only it knows its BPMS. The core
 * does NOT always wait. It waits only where VanillaBP has a reason to believe that this
 * adapter holds the workflow (see
 * {@code io.vanillabp.integration.spi.WorkflowAdapterCache}). So a workflow which really
 * does not exist still fails at once.
 *
 * @param window How long an {@link WorkflowAwareness#UNKNOWN_TO_BPMS} answer may still
 *        turn into {@link WorkflowAwareness#ACTIVE}. {@link Duration#ZERO} switches
 *        the waiting off
 * @param interval How long to wait between two probes. Ignored for a zero window
 */
public record WorkflowVisibilityDelay(
                                      Duration window,
                                      Duration interval) {

  private static final WorkflowVisibilityDelay NONE = new WorkflowVisibilityDelay(
      Duration.ZERO, Duration.ZERO);

  /**
   * The answer of a BPMS which is consistent at once. An unknown workflow stays unknown,
   * however often it is asked.
   *
   * @return The zero delay
   */
  public static WorkflowVisibilityDelay none() {

    return NONE;

  }

  /**
   * Whether the core looks a second time at all. A window which is missing, zero or
   * negative means no. That is what {@link #none()} reports.
   *
   * @return Whether waiting for a workflow to become visible makes sense at all
   */
  public boolean isWaiting() {

    return (window != null) && !window.isZero() && !window.isNegative();

  }

}
