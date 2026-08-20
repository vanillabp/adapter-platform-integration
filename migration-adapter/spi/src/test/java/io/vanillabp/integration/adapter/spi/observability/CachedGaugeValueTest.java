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
 * Story 92: a gauge is read on every collection, so what stands behind it must not be a
 * query. What is pinned here is the promise the class makes - one measurement per
 * window whoever asks, a failure which does not stay, and a way to switch the holding
 * off for a test which needs to see the real value.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CachedGaugeValueTest {

  /**
   * A clock the test moves itself, so no window has to be waited out.
   */
  private final AtomicLong nanos = new AtomicLong();

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

    final var collectors = 8;
    final var measurements = new AtomicInteger();
    final var insideTheMeasurement = new CountDownLatch(1);
    final var value = held(
        Duration.ofSeconds(10),
        () -> {
          measurements.incrementAndGet();
          try {
            // hold the first measurement open so the others really are concurrent
            insideTheMeasurement.await(5, TimeUnit.SECONDS);
          } catch (final InterruptedException e) {
            Thread
                .currentThread()
                .interrupt();
          }
          return OptionalLong.of(7);
        });

    final var allReady = new CountDownLatch(collectors);
    final var allDone = new CountDownLatch(collectors);
    try (var collecting = Executors.newFixedThreadPool(collectors)) {
      for (var collector = 0; collector < collectors; ++collector) {
        collecting
            .execute(() -> {
              allReady.countDown();
              try {
                assertEquals(OptionalLong.of(7), value.get());
              } finally {
                allDone.countDown();
              }
            });
      }
      assertTrue(allReady.await(5, TimeUnit.SECONDS));
      // the first collector is now inside the measurement, the others are queued
      Thread.sleep(100);
      insideTheMeasurement.countDown();
      assertTrue(allDone.await(10, TimeUnit.SECONDS));
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
