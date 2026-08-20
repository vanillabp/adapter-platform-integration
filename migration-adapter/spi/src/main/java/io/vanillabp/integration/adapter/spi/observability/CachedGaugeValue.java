package io.vanillabp.integration.adapter.spi.observability;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Holds the value of a gauge whose measurement costs something, so that reading the
 * metric does not.
 * <p>
 * <b>The rule this exists for.</b> A gauge is read every time somebody collects, and
 * nobody expects looking to be expensive. Prometheus scrapes every fifteen seconds by
 * default, a dashboard asks in between, and every instance of the application answers
 * separately - a gauge backed by a database query turns a dashboard into load on the
 * very system it is watching. VanillaBP's promise is therefore that reading a metric
 * never costs more than reading a number, and this class is how it is kept: whatever
 * really has to be asked is asked at most once per
 * {@code vanillabp.metrics.gauge-cache}, and everybody else gets the number that came
 * back.
 * <p>
 * It lives in the adapter SPI because the promise is not the platform's alone. A BPMS
 * adapter registering a gauge of its own - the backlog of a queue, the size of
 * something the BPMS holds - keeps the same promise with the same class. A value which
 * is already in memory (a counter, the free permits of a semaphore) needs none of this
 * and should not be wrapped: caching it would only make it stale.
 * <p>
 * <b>What it guarantees.</b>
 * <ul>
 * <li>Within one time-to-live the supplier runs exactly once, however many collectors
 * ask. A second thread arriving while the first one is asking waits for that answer
 * rather than starting a second query.</li>
 * <li>A supplier which throws does not reach the metrics backend and does not poison
 * the gauge: the failure is answered with "no measurement" for the rest of the window
 * and the next window asks again. A database which was down for a minute is therefore
 * back in the metric a minute later, without anybody having hammered it meanwhile.</li>
 * <li>A time-to-live of zero (or a negative one) switches the holding off, which is
 * what a test wants when it has just changed something and needs to see it.</li>
 * </ul>
 */
public final class CachedGaugeValue {

  /**
   * What the supplier answered and when, as one object so a reader never sees a value
   * from one measurement with the timestamp of another.
   */
  private record Measurement(
                             long takenAtNanos,
                             OptionalLong value) {
  }

  private final Duration timeToLive;

  private final Supplier<OptionalLong> measure;

  /**
   * The clock, in nanoseconds of an arbitrary origin - injectable so a test does not
   * have to wait for a window to pass.
   */
  private final LongSupplier nanoClock;

  /**
   * Serializes the measuring, so concurrent collectors produce one query and not one
   * each.
   */
  private final ReentrantLock measuring = new ReentrantLock();

  private volatile Measurement current;

  /**
   * @param timeToLive How long one measurement is reused; zero or negative switches
   *          the holding off
   * @param measure What really has to be asked, answering
   *          {@link OptionalLong#empty()} where it cannot say
   */
  public CachedGaugeValue(
      final Duration timeToLive,
      final Supplier<OptionalLong> measure) {

    this(timeToLive, measure, System::nanoTime);

  }

  /**
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
   * Wraps an expensive measurement into a supplier a gauge can read on every
   * collection.
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
   * The value a gauge reports right now: the held measurement while it is young
   * enough, a fresh one otherwise.
   *
   * @return The value, or {@link OptionalLong#empty()} where the measurement could not
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
      // somebody else may have measured while this thread waited for the lock - that
      // answer is what this collection reports, instead of asking a second time
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
   * Asks the supplier, turning a failure into "no measurement". A gauge must never be
   * the reason an application fails, and a metrics backend calling it must never see
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
