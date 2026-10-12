package io.vanillabp.integration.test.electioncost;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;

/**
 * The BPMS double of this scenario. It plays a cluster whose exporter is behind. The
 * workflow runs, but the read model which the awareness probe searches does not show it
 * yet.
 * <p>
 * A test switches the lag on. Afterwards it reads how often the core asked. That turns
 * the waiting into a number instead of a suspicion.
 */
public class ALaggingReadModel implements DummyTaskAwarenessSource {

  /**
   * The window the adapter reports. The default is the window of the Camunda 8 adapter
   * without configuration. So the measurement shows what an application meets.
   */
  private final AtomicReference<Duration> window = new AtomicReference<>(Duration.ofSeconds(10));

  /**
   * How often the core asks while it waits. The Camunda 8 adapter does not make it
   * configurable either. So it is a constant there, not a property.
   */
  public static final Duration PROBE_INTERVAL = Duration.ofMillis(250);

  /**
   * Whether the read model shows the workflow. A test starts with a visible workflow, so
   * the election writes its hint. Then the test hides the workflow.
   */
  private final AtomicReference<Boolean> visible = new AtomicReference<>(Boolean.TRUE);

  /**
   * How often the core asked since the last {@link #forgetTheWorkflow(Duration)}.
   */
  private final AtomicInteger probes = new AtomicInteger();

  /**
   * Answers as a read model which has caught up.
   */
  public void showTheWorkflow() {

    visible.set(Boolean.TRUE);

  }

  /**
   * Answers as a read model which is behind, for the given window.
   *
   * @param lag How long the adapter says its read model may need
   */
  public void forgetTheWorkflow(
      final Duration lag) {

    window.set(lag);
    visible.set(Boolean.FALSE);
    probes.set(0);

  }

  /**
   * @return How often the core asked since the workflow was hidden
   */
  public int probes() {

    return probes.get();

  }

  /**
   * @return The window this double reports
   */
  public Duration window() {

    return window.get();

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    probes.incrementAndGet();
    return visible.get()
        ? WorkflowAwareness.ACTIVE
        : WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  @Override
  public WorkflowVisibilityDelay workflowVisibilityDelay(
      final String adapterId) {

    return new WorkflowVisibilityDelay(window.get(), PROBE_INTERVAL);

  }

}
