package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

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
  @DisplayName("Waiting for a read model lasts as long as the attempts would, unless it is set")
  public void theWaitForAReadModelLastsAsLongAsTheAttempts() {

    final var properties = new PhaseTwoOutboxProperties();

    assertNull(properties.getWaitForVisibilityAtMost(), "nothing is set by default");
    // the first attempt runs at once and the fiftieth failed one blocks the entry, so
    // forty-nine distances lie between: the four hours the attempt budget is chosen for,
    // given to a read model which is behind as well
    assertEquals(Duration.ofMinutes(232).plusSeconds(30), properties.waitForVisibilityAtMost());

    // the span follows the attempt settings, so an application which gives a failing
    // BPMS less time gives a lagging read model less time too
    properties.setBlockAfterAttempts(3);
    assertEquals(Duration.ofSeconds(90), properties.waitForVisibilityAtMost());

    properties.setWaitForVisibilityAtMost(Duration.ofMinutes(20));
    assertEquals(Duration.ofMinutes(20), properties.waitForVisibilityAtMost(), "a value which is set wins");

  }

  @Test
  @DisplayName("An entry has waited long enough once the time passed since it was written")
  public void anEntryHasWaitedLongEnoughOnceTheTimePassed() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setWaitForVisibilityAtMost(Duration.ofMinutes(20));
    final var writtenAt = Instant.parse("2026-10-04T13:55:20Z");

    assertFalse(properties.hasWaitedForVisibilityLongEnough(writtenAt, writtenAt.plus(Duration.ofMinutes(19))));
    assertTrue(properties.hasWaitedForVisibilityLongEnough(writtenAt, writtenAt.plus(Duration.ofMinutes(20))));
    assertFalse(
        properties.hasWaitedForVisibilityLongEnough(null, writtenAt.plus(Duration.ofDays(1))),
        "an entry whose store does not know when it was written is never blocked for waiting");

  }

  @Test
  @DisplayName("A time for waiting on a read model which is not longer than zero ends the startup")
  public void aWaitOfZeroEndsTheStartup() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setWaitForVisibilityAtMost(Duration.ZERO);

    final var refused = assertThrows(IllegalStateException.class, properties::validateVisibilityWait);
    assertTrue(refused.getMessage().contains("'vanillabp.outbox.wait-for-visibility-at-most' is 'PT0S'"), refused
        .getMessage());
    assertTrue(refused.getMessage().contains("'vanillabp.outbox.block-after-attempts'"), refused.getMessage());

    properties.setWaitForVisibilityAtMost(Duration.ofMinutes(1));
    properties.validateVisibilityWait();
    properties.setWaitForVisibilityAtMost(null);
    properties.validateVisibilityWait();

  }

  @Test
  @DisplayName("An unconfigured outbox section yields the defaults")
  public void unconfiguredSectionYieldsDefaults() {

    final var properties = new MigrationAdapterProperties();

    assertNotNull(properties.getOutbox());
    assertEquals(Duration.ofSeconds(10), properties.getOutbox().getPollInterval());

  }

}
