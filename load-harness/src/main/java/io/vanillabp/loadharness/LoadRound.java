package io.vanillabp.loadharness;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vanillabp.loadharness.LoadDriver.Operation;
import io.vanillabp.loadharness.record.Metric;
import io.vanillabp.loadharness.record.RoundResult;

/**
 * One round of the load run, inside one JVM. The weekly run starts a fresh JVM for every
 * round, alternating the baseline and the candidate.
 * <p>
 * A round runs the fixed work three times:
 * <ol>
 * <li>a small work, which warms the JVM up and gives the statement counts,</li>
 * <li>a work of {@link Sizes#first()} workflows, after which the live set is read,</li>
 * <li>a work of {@link Sizes#second()} workflows, which is timed, and after which the live
 * set is read again.</li>
 * </ol>
 * The live set is the heap in use after a forced collection. One number says nothing. The
 * difference between the two, divided by the workflows in between, is what a workflow leaves
 * behind. Once the work is done, that has to be close to nothing. A slope which is not flat
 * is a leak.
 */
public final class LoadRound {

  /**
   * The names of the meters of VanillaBP which the round reads. They are the names
   * {@code VanillaBpMetrics} of the migration adapter publishes. The harness reads them by
   * name, so it does not depend on the runtime it measures.
   */
  static final String OUTBOX_DISPATCH_LAG = "vanillabp.outbox.dispatch.lag";

  static final String OUTBOX_OLDEST_PENDING_AGE = "vanillabp.outbox.oldest.pending.age";

  static final String TASK_REDELIVERIES_CONCURRENT = "vanillabp.task.redeliveries.concurrent";

  static final String OUTBOX_DISCARDED = "vanillabp.outbox.discarded";

  static final String OUTBOX_BLOCKED = "vanillabp.outbox.blocked";

  static final String TASK_DELIVERIES = "vanillabp.task.deliveries";

  /**
   * How often the gauges are read while the timed work runs.
   */
  private static final Duration SAMPLING = Duration.ofMillis(20);

  private LoadRound() {
  }

  /**
   * The three sizes of a round.
   *
   * @param counting The small work which warms up and counts statements
   * @param first The first size for the live set
   * @param second The second size for the live set, which is also the timed work. Ten
   *          times the first is what the weekly run uses
   */
  public record Sizes(
                      int counting,
                      int first,
                      int second) {
  }

  /**
   * Runs one round.
   *
   * @param application The application to drive
   * @param driver The driver, which the application registered as a hook of the double
   * @param work The work, whose number of workflows is replaced by the sizes
   * @param sizes The three sizes
   * @param timeout How long each of the three works may take
   * @return The numbers of this round
   */
  public static RoundResult run(
      final LoadApplication application,
      final LoadDriver driver,
      final FixedWork work,
      final Sizes sizes,
      final Duration timeout) {

    final var values = new EnumMap<Metric, Double>(Metric.class);
    var failed = 0;

    final var counting = driver.run(application, work.withWorkflows(sizes.counting()), timeout);
    failed += counting.failedWorkflows();
    values.put(Metric.STATEMENTS_START, median(counting.statements().get(Operation.START)));
    values.put(Metric.STATEMENTS_TASK, median(counting.statements().get(Operation.TASK)));
    values.put(Metric.STATEMENTS_COMPLETION, median(counting.statements().get(Operation.COMPLETION)));
    values.put(Metric.STATEMENTS_CORRELATION, median(counting.statements().get(Operation.CORRELATION)));

    failed += driver.run(application, work.withWorkflows(sizes.first()), timeout).failedWorkflows();
    final var liveSetAfterFirst = liveSet();

    final var lagBefore = lag(application.meters());
    final var gcBefore = gcMillis();
    final var sampled = new Sampled();
    final var sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "load-sampler");
      thread.setDaemon(true);
      return thread;
    });
    sampler
        .scheduleAtFixedRate(
            () -> sampled.read(application),
            0,
            SAMPLING.toMillis(),
            TimeUnit.MILLISECONDS);
    final WorkResultHolder timed = new WorkResultHolder();
    try {
      timed.result = driver.run(application, work.withWorkflows(sizes.second()), timeout);
    } finally {
      sampler.shutdownNow();
    }
    failed += timed.result.failedWorkflows();
    final var gcAfter = gcMillis();
    final var lagAfter = lag(application.meters());
    final var liveSetAfterSecond = liveSet();

    final var wallMillis = timed.result.wallTime().toNanos() / 1_000_000.0;
    values.put(Metric.WALL_TIME, wallMillis);
    values.put(Metric.OUTBOX_WAIT, lagAfter.meanMillisSince(lagBefore));
    values.put(Metric.OLDEST_WAITING, sampled.oldestWaiting.get());
    values.put(Metric.CONNECTIONS_ACTIVE, sampled.active.get());
    values.put(Metric.CONNECTIONS_PENDING, sampled.pending.get());
    values.put(Metric.LIVE_SET_PER_WORKFLOW, (liveSetAfterSecond - liveSetAfterFirst) / (double) sizes.second());
    values.put(Metric.GC_SHARE, wallMillis <= 0
        ? 0
        : (gcAfter - gcBefore) / wallMillis);

    final var meters = application.meters();
    values.put(Metric.REDELIVERIES_CONCURRENT, counted(meters, TASK_REDELIVERIES_CONCURRENT, null));
    values.put(Metric.OUTBOX_DISCARDED, counted(meters, OUTBOX_DISCARDED, null));
    values.put(Metric.OUTBOX_BLOCKED, counted(meters, OUTBOX_BLOCKED, null));
    values.put(Metric.DELIVERIES_FAILED, counted(meters, TASK_DELIVERIES, "failed"));
    values.put(Metric.WORKFLOWS_FAILED, (double) failed);

    return new RoundResult(values);

  }

  private static final class WorkResultHolder {

    private LoadDriver.WorkResult result;

  }

  /**
   * The highest values of the gauges while the timed work ran.
   */
  private static final class Sampled {

    private final AtomicReference<Double> oldestWaiting = new AtomicReference<>(0.0);

    private final AtomicReference<Double> active = new AtomicReference<>(0.0);

    private final AtomicReference<Double> pending = new AtomicReference<>(0.0);

    private void read(
        final LoadApplication application) {

      final var oldest = application
          .meters()
          .find(OUTBOX_OLDEST_PENDING_AGE)
          .gauges()
          .stream()
          .mapToDouble(Gauge::value)
          .filter(value -> !Double.isNaN(value))
          .max()
          .orElse(0);
      oldestWaiting.accumulateAndGet(oldest, Math::max);
      final var pool = application.connectionPool();
      active.accumulateAndGet((double) pool.active(), Math::max);
      pending.accumulateAndGet((double) pool.pending(), Math::max);

    }

  }

  /**
   * The sum of all timers of the outbox lag, at one moment.
   */
  private record Lag(
                     long count,
                     double totalMillis) {

    private double meanMillisSince(
        final Lag before) {

      final var count = this.count - before.count;
      return count == 0
          ? 0
          : (totalMillis - before.totalMillis) / count;

    }

  }

  private static Lag lag(
      final MeterRegistry meters) {

    final var timers = meters.find(OUTBOX_DISPATCH_LAG).timers();
    return new Lag(
        timers.stream().mapToLong(Timer::count).sum(), timers.stream()
            .mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum());

  }

  private static double counted(
      final MeterRegistry meters,
      final String name,
      final String outcome) {

    final var search = outcome == null
        ? meters.find(name)
        : meters.find(name).tag("outcome", outcome);
    return search.counters().stream().mapToDouble(Counter::count).sum() + search.gauges().stream()
        .mapToDouble(Gauge::value).filter(value -> !Double.isNaN(value)).sum();

  }

  private static long liveSet() {

    final var memory = ManagementFactory.getMemoryMXBean();
    // twice, because a finalizer or a reference queue may free more in a second round
    System.gc();
    System.gc();
    return memory.getHeapMemoryUsage().getUsed();

  }

  private static long gcMillis() {

    return ManagementFactory
        .getGarbageCollectorMXBeans()
        .stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionTime()))
        .sum();

  }

  /**
   * The median of a list of counts. Every count of one operation should be the same. The
   * median keeps a first call which ran an extra statement, for example to warm a cache,
   * from moving the number.
   */
  static double median(
      final List<Long> values) {

    if (values.isEmpty()) {
      return 0;
    }
    final var sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    final var middle = sorted.size() / 2;
    return sorted.size() % 2 == 1
        ? sorted.get(middle)
        : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;

  }

}
