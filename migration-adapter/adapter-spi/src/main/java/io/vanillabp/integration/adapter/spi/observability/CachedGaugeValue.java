package io.vanillabp.integration.adapter.spi.observability;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Holds the value of a gauge whose measurement is expensive, so that reading the metric
 * is cheap.
 * <p>
 * <b>Why this exists.</b> A gauge is read every time somebody collects metrics, and
 * nobody expects looking to be expensive. Prometheus scrapes every fifteen seconds by
 * default, a dashboard asks in between, and every instance of the application answers
 * on its own. So a gauge backed by a database query turns a dashboard into load on the
 * very system it watches. VanillaBP promises that reading a metric never costs more
 * than reading a number, and this class keeps that promise. Whatever really has to be
 * asked is asked at most once per {@code vanillabp.metrics.gauge-cache}. Everybody else
 * gets the number which came back.
 * <p>
 * It is in the adapter SPI because the promise is not only the platform's. A BPMS
 * adapter which registers a gauge of its own keeps the same promise with the same class.
 * Examples are the backlog of a queue, or the size of something the BPMS holds. A value
 * which is already in memory (a counter, the free permits of a semaphore) needs none of
 * this. Do not wrap it, because caching it would only make it stale.
 * <p>
 * <b>What it guarantees.</b>
 * <ul>
 * <li>Within one time-to-live the supplier runs exactly once, however many collectors
 * ask. If a second thread arrives while the first one is asking, it waits for that
 * answer. It does not start a second query.</li>
 * <li>A supplier which throws does not reach the metrics backend and does not break
 * the gauge. The answer is "no measurement" for the rest of the window, and the next
 * window asks again. So a database which was down for a minute is back in the metric a
 * minute later, and nobody hammered it in the meantime.</li>
 * <li>A time-to-live of zero (or a negative one) switches the holding off. A test wants
 * this when it has just changed something and needs to see it.</li>
 * </ul>
 * <p>
 * Why every measurement which is not already in memory is held for a window is decision 18 in the
 * repository's DECISIONS.md.
 */
public final class CachedGaugeValue {

  /**
   * What the supplier answered and when, in one object. So a reader never sees the value
   * of one measurement with the timestamp of another.
   */
  private record Measurement(
                             long takenAtNanos,
                             OptionalLong value) {
  }

  private final Duration timeToLive;

  private final Supplier<OptionalLong> measure;

  /**
   * The clock, in nanoseconds from any starting point. A test can inject its own, so it
   * does not have to wait for a window to pass.
   */
  private final LongSupplier nanoClock;

  /**
   * Lets only one thread measure at a time. So concurrent collectors cause one query, not
   * one each.
   */
  private final ReentrantLock measuring = new ReentrantLock();

  private volatile Measurement current;

  /**
   * Holds one measurement for the given window. However many collectors read the gauge in
   * that time, the supplier runs once.
   *
   * @param timeToLive How long one measurement is reused. Zero or negative switches
   *          the holding off
   * @param measure What really has to be asked. It answers
   *          {@link OptionalLong#empty()} if it cannot say
   */
  public CachedGaugeValue(
      final Duration timeToLive,
      final Supplier<OptionalLong> measure) {

    this(timeToLive, measure, System::nanoTime);

  }

  /**
   * The same with a clock of its own. A test uses it to age a measurement, instead of
   * waiting for the window to pass.
   *
   * @param timeToLive How long one measurement is reused
   * @param measure What really has to be asked
   * @param nanoClock The clock to age a measurement by (tests hand in their own)
   */
  public CachedGaugeValue(
      final Duration timeToLive,
      final Supplier<OptionalLong> measure,
      final LongSupplier nanoClock) {

    this.timeToLive = timeToLive == null
        ? Duration.ZERO
        : timeToLive;
    this.measure = measure;
    this.nanoClock = nanoClock;

  }

  /**
   * Wraps an expensive measurement into a supplier which a gauge can read every time
   * metrics are collected.
   *
   * @param timeToLive How long one measurement is reused
   * @param measure What really has to be asked
   * @return A supplier answering from the held measurement
   */
  public static Supplier<OptionalLong> holding(
      final Duration timeToLive,
      final Supplier<OptionalLong> measure) {

    final var held = new CachedGaugeValue(timeToLive, measure);
    return held::get;

  }

  /**
   * The value a gauge reports right now. It is the held measurement while that is young
   * enough, and a fresh one otherwise.
   *
   * @return The value, or {@link OptionalLong#empty()} if the measurement could not
   *         be taken
   */
  public OptionalLong get() {

    if (timeToLive.isZero() || timeToLive.isNegative()) {
      return takeMeasurement();
    }

    final var held = current;
    if (isYoungEnough(held)) {
      return held.value();
    }

    measuring.lock();
    try {
      // another thread may have measured while this one waited for the lock. Then this
      // collection reports that answer and does not ask a second time
      final var measured = current;
      if (isYoungEnough(measured)) {
        return measured.value();
      }
      final var fresh = new Measurement(nanoClock.getAsLong(), takeMeasurement());
      current = fresh;
      return fresh.value();
    } finally {
      measuring.unlock();
    }

  }

  private boolean isYoungEnough(
      final Measurement measurement) {

    return (measurement != null) && ((nanoClock.getAsLong() - measurement.takenAtNanos()) < timeToLive.toNanos());

  }

  /**
   * Asks the supplier and turns a failure into "no measurement". A gauge must never be
   * the reason why an application fails. A metrics backend which calls it must never see
   * an exception.
   */
  private OptionalLong takeMeasurement() {

    try {
      final var measured = measure.get();
      return measured == null
          ? OptionalLong.empty()
          : measured;
    } catch (final RuntimeException e) {
      org.slf4j.LoggerFactory
          .getLogger(CachedGaugeValue.class)
          .debug("A gauge's measurement failed and is reported as absent", e);
      return OptionalLong.empty();
    }

  }

}
