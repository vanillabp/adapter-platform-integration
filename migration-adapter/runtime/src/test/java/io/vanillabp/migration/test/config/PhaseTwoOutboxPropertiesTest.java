package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Pins the outbox defaults ONCE, in the core - they are user-visible (documented in
 * the wiki) and consumed by all platform outbox implementations. Changing a default
 * has to be an explicit decision, not a side effect.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoOutboxPropertiesTest {

  @Test
  @DisplayName("The outbox defaults are PT10S / PT30S / PT5M / 50 / true / P7D")
  public void defaultsArePinned() {

    final var properties = new PhaseTwoOutboxProperties();

    assertEquals(Duration.ofSeconds(10), properties.getPollInterval());
    assertEquals(Duration.ofSeconds(30), properties.getAttemptFrequency());
    assertEquals(Duration.ofMinutes(5), properties.getMaxAttemptFrequency());
    assertEquals(50, properties.getBlockAfterAttempts());
    assertTrue(properties.isCreateSchema());
    assertEquals(Duration.ofDays(7), properties.getRetention());

  }

  @Test
  @DisplayName("The backoff doubles from attempt-frequency and stops at the cap")
  public void theBackoffGrowsAndIsCapped() {

    final var properties = new PhaseTwoOutboxProperties();

    // the first retry keeps attempt-frequency, because most failures are momentary
    assertEquals(Duration.ofSeconds(30), properties.attemptDelay(0));
    assertEquals(Duration.ofSeconds(60), properties.attemptDelay(1));
    assertEquals(Duration.ofSeconds(120), properties.attemptDelay(2));
    assertEquals(Duration.ofSeconds(240), properties.attemptDelay(3));
    // 480 seconds would be next, so the cap decides from here on
    assertEquals(Duration.ofMinutes(5), properties.attemptDelay(4));
    assertEquals(Duration.ofMinutes(5), properties.attemptDelay(49));
    // the exponent is bounded before it is computed: 2^63 overflows, and
    // block-after-attempts is configurable
    assertEquals(Duration.ofMinutes(5), properties.attemptDelay(1_000));

  }

  @Test
  @DisplayName("The shipped defaults span an outage of about four hours")
  public void theAttemptBudgetSpansHours() {

    final var properties = new PhaseTwoOutboxProperties();

    var total = Duration.ZERO;
    for (var attempt = 0; attempt < properties.getBlockAfterAttempts(); attempt++) {
      total = total.plus(properties.attemptDelay(attempt));
    }

    // said in numbers, because this is the outage length the defaults are chosen for:
    // a cluster upgrade has to end inside it, not a network hiccup
    assertEquals(Duration.ofMinutes(237).plusSeconds(30), total);
    assertTrue(total.compareTo(Duration.ofHours(3)) > 0, "an outage of three hours is survived");

  }

  @Test
  @DisplayName("An unconfigured outbox section yields the defaults")
  public void unconfiguredSectionYieldsDefaults() {

    final var properties = new MigrationAdapterProperties();

    assertNotNull(properties.getOutbox());
    assertEquals(Duration.ofSeconds(10), properties.getOutbox().getPollInterval());

  }

}
