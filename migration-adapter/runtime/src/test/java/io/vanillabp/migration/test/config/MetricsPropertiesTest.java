package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MetricsProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How long the measurement of a gauge which has to ask somebody is reused.
 * The default is one collection interval, zero is the way to switch the holding off,
 * and a negative span is a typo the boot names.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MetricsPropertiesTest {

  @Test
  @DisplayName("Nothing configured means ten seconds")
  public void theDefaultIsOneCollectionInterval() {

    assertEquals(
        Duration.ofSeconds(10),
        new MetricsProperties().resolvedGaugeCache(),
        "a little under Prometheus' default scrape of fifteen seconds");
    assertEquals(
        Duration.ofSeconds(10),
        MetricsProperties.DEFAULT_GAUGE_CACHE,
        "and the constant says the same as the ISO notation the messages use");

    final var configured = new MetricsProperties();
    configured.setGaugeCache(Duration.ofMinutes(1));
    assertEquals(Duration.ofMinutes(1), configured.resolvedGaugeCache());

    configured.setGaugeCache(null);
    assertEquals(
        Duration.ofSeconds(10),
        configured.resolvedGaugeCache(),
        "a binder mapping an absent section onto null must not cost the default");

  }

  @Test
  @DisplayName("Zero is a legitimate value: measure on every collection")
  public void zeroSwitchesTheHoldingOff() {

    final var properties = new MetricsProperties();
    properties.setGaugeCache(Duration.ZERO);

    assertDoesNotThrow(properties::validate);
    assertEquals(Duration.ZERO, properties.resolvedGaugeCache());

  }

  @Test
  @DisplayName("A negative span fails the boot naming the property and the default")
  public void aNegativeSpanFailsTheBoot() {

    final var properties = new MetricsProperties();
    properties.setGaugeCache(Duration.ofSeconds(-1));

    final var failure = assertThrows(IllegalStateException.class, properties::validate);

    final var message = failure.getMessage();
    assertTrue(message.contains(MetricsProperties.GAUGE_CACHE_PROPERTY), message);
    assertTrue(message.contains("PT10S"), "names the default: "
        + message);
    assertTrue(message.contains("PT0S"), "and the way to switch the holding off: "
        + message);

  }

}
