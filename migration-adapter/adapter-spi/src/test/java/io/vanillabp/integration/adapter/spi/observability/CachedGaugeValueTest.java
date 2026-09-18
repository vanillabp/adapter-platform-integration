package io.vanillabp.integration.adapter.spi.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A gauge is read on every collection, so what stands behind it must not be a
 * query. What is pinned here is the promise the class makes - one measurement per
 * window whoever asks, a failure which does not stay, and a way to switch the holding
 * off for a test which needs to see the real value.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CachedGaugeValueTest {

  /**
   * How long a wait for collectors goes on before the test gives up. It guards against a
   * thread which never got its turn and it measures nothing: what the concurrency test
   * claims is read from the state of the collectors, so a loaded machine makes it slower
   * rather than red.
   */
  private static final long UNTIL_A_COLLECTOR_COUNTS_AS_STUCK = 30000;

  /**
   * A clock the test moves itself, so no window has to be waited out.
   */
  private final AtomicLong nanos = new AtomicLong();

  /**
   * Waits until the given number of collectors wait for the measurement one of them is
   * taking. A thread queued on the lock of the held value is parked, so
   * {@link Thread.State#WAITING} is how that shows from the outside. The collector
   * INSIDE the measurement waits with a timeout and is therefore
   * {@link Thread.State#TIMED_WAITING}, which keeps the two apart.
   *
   * @param collectors The threads which asked the held value
   * @param parked How many of them have to be waiting for the measurement
   */
  private static void awaitCollectorsParkedOnTheMeasurement(
      final java.util.Collection<Thread> collectors,
      final int parked) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_A_COLLECTOR_COUNTS_AS_STUCK;
    while (waitingCollectors(collectors) < parked) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected %d collectors waiting for the measurement, saw %d"
              .formatted(parked, waitingCollectors(collectors)));
      Thread.sleep(10);
    }

  }

  private static long waitingCollectors(
      final java.util.Collection<Thread> collectors) {

    return collectors
        .stream()
        .filter(collector -> collector.getState() == Thread.State.WAITING)
        .count();

  }

  private CachedGaugeValue held(
      final Duration timeToLive,
      final java.util.function.Supplier<OptionalLong> measure) {

    return new CachedGaugeValue(timeToLive, measure, nanos::get);

  }

  @Test
  @DisplayName("Within one window the measurement is taken once, however often it is read")
  public void oneMeasurementPerWindow() {

    final var measurements = new AtomicInteger();
    final var value = held(
        Duration.ofSeconds(10),
        () -> OptionalLong.of(measurements.incrementAndGet()));

    assertEquals(OptionalLong.of(1), value.get());
    assertEquals(OptionalLong.of(1), value.get());
    nanos.addAndGet(Duration.ofSeconds(9).toNanos());
    assertEquals(OptionalLong.of(1), value.get());

    assertEquals(1, measurements.get(), "a scrape and a dashboard together cost one query");

  }

  @Test
  @DisplayName("Once the window passed the next read measures again")
  public void theNextWindowMeasuresAgain() {

    final var measurements = new AtomicInteger();
    final var value = held(
        Duration.ofSeconds(10),
        () -> OptionalLong.of(measurements.incrementAndGet()));

    assertEquals(OptionalLong.of(1), value.get());
    nanos.addAndGet(Duration.ofSeconds(10).toNanos());

    assertEquals(OptionalLong.of(2), value.get(), "the number an operator sees stays current");
    assertEquals(2, measurements.get());

  }

  @Test
  @DisplayName("Without a window every read measures")
  public void aZeroWindowSwitchesTheHoldingOff() {

    final var measurements = new AtomicInteger();
    final var value = held(
        Duration.ZERO,
        () -> OptionalLong.of(measurements.incrementAndGet()));

    value.get();
    value.get();
    value.get();

    assertEquals(3, measurements.get(), "which is what a test wants after it changed something");

  }

  @Test
  @DisplayName("A failing measurement is absent for the window and gone in the next one")
  public void aFailureDoesNotStay() {

    final var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
    final var measurements = new AtomicInteger();
    final var value = held(
        Duration.ofSeconds(10),
        () -> {
          measurements.incrementAndGet();
          if (failing.get()) {
            throw new IllegalStateException("the database is not there");
          }
          return OptionalLong.of(42);
        });

    assertTrue(value.get().isEmpty(), "the exception must not reach the metrics backend");
    assertTrue(value.get().isEmpty());
    assertEquals(1, measurements.get(), "and a broken database is not asked again within the window");

    failing.set(false);
    nanos.addAndGet(Duration.ofSeconds(10).toNanos());

    assertEquals(OptionalLong.of(42), value.get(), "a database which came back is back in the metric");

  }

  @Test
  @DisplayName("A supplier answering nothing is passed through as nothing")
  public void anEmptyMeasurementStaysEmpty() {

    final var value = held(Duration.ofSeconds(10), OptionalLong::empty);

    assertFalse(value.get().isPresent());

  }

  @Test
  @DisplayName("Collectors arriving at the same moment produce one measurement, not one each")
  public void concurrentCollectorsShareOneMeasurement() throws Exception {

    final var howManyCollectors = 8;
    final var measurements = new AtomicInteger();
    final var insideTheMeasurement = new CountDownLatch(1);
    final var value = held(
        Duration.ofSeconds(10),
        () -> {
          measurements.incrementAndGet();
          try {
            // hold the first measurement open so the others really are concurrent
            insideTheMeasurement.await(UNTIL_A_COLLECTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);
          } catch (final InterruptedException e) {
            Thread
                .currentThread()
                .interrupt();
          }
          return OptionalLong.of(7);
        });

    final var collectors = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
    final var allDone = new CountDownLatch(howManyCollectors);
    try (var collecting = Executors.newFixedThreadPool(howManyCollectors)) {
      for (var collector = 0; collector < howManyCollectors; ++collector) {
        collecting
            .execute(() -> {
              collectors.add(Thread.currentThread());
              try {
                assertEquals(OptionalLong.of(7), value.get());
              } finally {
                allDone.countDown();
              }
            });
      }
      // the others are concurrent once they are parked on the lock the first one holds,
      // and that is what the wait below reads. A wait of a fixed length would only guess
      // at it: on a machine carrying several builds eight threads need longer to get
      // going than any number somebody writes here
      awaitCollectorsParkedOnTheMeasurement(collectors, howManyCollectors - 1);
      insideTheMeasurement.countDown();
      assertTrue(
          allDone.await(UNTIL_A_COLLECTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "a collector never came back from the measurement");
    }

    assertEquals(
        1,
        measurements.get(),
        "eight collectors at the same moment must not become eight queries");

  }

  @Test
  @DisplayName("The factory hands out a supplier a gauge can read")
  public void theFactoryProducesASupplier() {

    final var measurements = new AtomicInteger();
    final var gaugeValue = CachedGaugeValue
        .holding(
            Duration.ofMinutes(5),
            () -> OptionalLong.of(measurements.incrementAndGet()));

    assertEquals(OptionalLong.of(1), gaugeValue.get());
    assertEquals(OptionalLong.of(1), gaugeValue.get());

  }

}
