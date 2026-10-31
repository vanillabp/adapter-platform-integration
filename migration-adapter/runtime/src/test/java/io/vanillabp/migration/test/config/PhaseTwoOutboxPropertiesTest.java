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
 * Pins the outbox defaults ONCE, in the core. Users see them (the wiki documents them), and
 * all outbox implementations of the platforms use them. Changing a default has to be an
 * explicit decision, not a side effect.
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
    // the exponent is bounded before it is computed. 2^63 overflows, and
    // block-after-attempts can be configured
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

    // said in numbers, because the defaults are chosen for this length of an outage. A
    // cluster upgrade has to end inside it, not just a network hiccup
    assertEquals(Duration.ofMinutes(237).plusSeconds(30), total);
    assertTrue(total.compareTo(Duration.ofHours(3)) > 0, "an outage of three hours is survived");

  }

  @Test
  @DisplayName("Waiting for a read model lasts as long as the attempts would, unless it is set")
  public void theWaitForAReadModelLastsAsLongAsTheAttempts() {

    final var properties = new PhaseTwoOutboxProperties();

    assertNull(properties.getWaitForVisibilityAtMost(), "nothing is set by default");
    // the first attempt runs at once, and the fiftieth failed one blocks the entry. So
    // forty-nine waits lie between. A read model which is behind gets the same four hours
    // for which the attempt budget is chosen
    assertEquals(Duration.ofMinutes(232).plusSeconds(30), properties.waitForVisibilityAtMost());

    // the span follows the attempt settings. So an application which gives a failing BPMS
    // less time also gives a lagging read model less time
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
  @DisplayName("A deadline the adapter names replaces wait-for-visibility-at-most, in both directions")
  public void aDeadlineReplacesTheLimit() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setWaitForVisibilityAtMost(Duration.ofMinutes(20));
    final var writtenAt = Instant.parse("2026-10-31T09:00:00Z");
    final var later = writtenAt.plus(Duration.ofHours(3));
    final var earlier = writtenAt.plus(Duration.ofMinutes(5));

    assertEquals(writtenAt.plus(Duration.ofMinutes(20)), properties.waitEndsAt(writtenAt, null));
    assertEquals(later, properties.waitEndsAt(writtenAt, later));
    assertEquals(later, properties.waitEndsAt(null, later), "the deadline needs no moment of writing");
    assertNull(properties.waitEndsAt(null, null));
    assertFalse(
        properties.hasWaitedLongEnough(writtenAt, later, writtenAt.plus(Duration.ofHours(1))),
        "a deadline after the limit keeps the entry waiting past the limit");
    assertTrue(properties.hasWaitedLongEnough(writtenAt, later, later));
    assertTrue(
        properties.hasWaitedLongEnough(writtenAt, earlier, earlier),
        "a deadline before the limit ends the wait at the deadline");
    assertFalse(properties.hasWaitedLongEnough(null, null, later));

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
