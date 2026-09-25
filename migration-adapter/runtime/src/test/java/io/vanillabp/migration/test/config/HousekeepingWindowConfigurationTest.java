package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.HousekeepingProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the housekeeping window accepts and what it refuses.
 * <p>
 * The refusal which matters most is the one a developer never meets: a container stands
 * on UTC unless somebody sets its zone, so "four in the morning" becomes four UTC, which
 * in most places is the middle of the working day. It is noticed when the application is
 * installed on a server, which is why the message names two ways out and both of them go
 * through an environment variable.
 */
@ExtendWith(SuppressOutputExtension.class)
public class HousekeepingWindowConfigurationTest {

  /**
   * The zone this JVM stood on before a test moved it. The test JVMs of this repository
   * are given a zone (see the surefire configuration of the root POM), and a test which
   * moves it puts it back.
   */
  private final TimeZone zoneOfThisJvm = TimeZone.getDefault();

  @AfterEach
  public void putTheZoneOfThisJvmBack() {

    TimeZone.setDefault(zoneOfThisJvm);

  }

  private static PhaseTwoOutboxProperties outboxWith(
      final HousekeepingProperties housekeeping) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setHousekeeping(housekeeping);
    return properties;

  }

  @Test
  @DisplayName("An application which configures nothing house-keeps between four and five")
  public void theDefaultWindowIsAnHourOfTheNight() {

    final var housekeeping = new PhaseTwoOutboxProperties().getHousekeeping();

    assertEquals(LocalTime.of(4, 0), housekeeping.getStart());
    assertEquals(LocalTime.of(5, 0), housekeeping.getEnd());
    assertEquals(ZoneId.systemDefault(), housekeeping.resolvedZone(), "no zone configured means the zone of the JVM");

  }

  @Test
  @DisplayName("A window whose start equals its end is refused, naming both keys and the default")
  public void aWindowWhichIsNoWindowIsRefused() {

    final var housekeeping = new HousekeepingProperties();
    housekeeping.setStart(LocalTime.of(4, 0));
    housekeeping.setEnd(LocalTime.of(4, 0));

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> outboxWith(housekeeping).validateHousekeeping());

    final var message = refused.getMessage();
    assertTrue(message.contains(HousekeepingProperties.START_PROPERTY), message);
    assertTrue(message.contains(HousekeepingProperties.END_PROPERTY), message);
    // what is lost while it stands
    assertTrue(message.contains("never removes either"), message);

  }

  @Test
  @DisplayName("A window which crosses midnight is accepted")
  public void aWindowAcrossMidnightIsAccepted() {

    final var housekeeping = new HousekeepingProperties();
    housekeeping.setStart(LocalTime.of(23, 0));
    housekeeping.setEnd(LocalTime.of(1, 0));
    housekeeping.setZone("Europe/Vienna");

    assertDoesNotThrow(() -> outboxWith(housekeeping).validateHousekeeping());

  }

  @Test
  @DisplayName("A zone nobody knows is refused, naming the key and how a zone is written")
  public void aZoneNobodyKnowsIsRefused() {

    final var housekeeping = new HousekeepingProperties();
    housekeeping.setZone("Middle/Earth");

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> outboxWith(housekeeping).validateHousekeeping());

    final var message = refused.getMessage();
    assertTrue(message.contains(HousekeepingProperties.ZONE_PROPERTY), message);
    assertTrue(message.contains("Middle/Earth"), message);
    // the message shows how a zone is written, so a reader needs no other source
    assertTrue(message.contains("Europe/Vienna"), message);

  }

  @Test
  @DisplayName("A JVM on UTC without a configured zone does not start, and the message names both ways out")
  public void aJvmOnUtcWithoutAZoneDoesNotStart() {

    TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> outboxWith(new HousekeepingProperties()).validateHousekeeping());

    final var message = refused.getMessage();
    // what would have happened
    assertTrue(message.contains("middle of the working day"), message);
    // and the two ways out, neither of which needs a new build
    assertTrue(message.contains("TZ=Europe/Vienna"), message);
    assertTrue(message.contains(HousekeepingProperties.ZONE_ENVIRONMENT_VARIABLE), message);
    assertTrue(message.contains(HousekeepingProperties.ZONE_PROPERTY), message);
    // and the way to keep UTC on purpose
    assertTrue(message.contains("Write 'UTC' there"), message);

  }

  @Test
  @DisplayName("Every spelling of UTC is refused, not only the one the JVM happens to use")
  public void everySpellingOfUtcIsRefused() {

    for (final var spelling : new String[]{
        "UTC", "Etc/UTC", "GMT", "Z", "Etc/GMT"
    }) {
      TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(spelling)));
      assertThrows(
          IllegalStateException.class,
          () -> outboxWith(new HousekeepingProperties()).validateHousekeeping(),
          "a JVM on '%s' means UTC as much as the others do".formatted(spelling));
    }

  }

  @Test
  @DisplayName("A JVM on UTC starts where UTC was configured on purpose")
  public void utcOnPurposeStarts() {

    TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));
    final var housekeeping = new HousekeepingProperties();
    housekeeping.setZone("UTC");

    assertDoesNotThrow(() -> outboxWith(housekeeping).validateHousekeeping());

  }

  @Test
  @DisplayName("A JVM which stands on a real zone starts without anybody saying anything")
  public void aJvmWithAZoneStarts() {

    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Vienna"));

    assertDoesNotThrow(() -> outboxWith(new HousekeepingProperties()).validateHousekeeping());

  }

  @Test
  @DisplayName("A binder which mapped the absent section onto nothing costs no defaults")
  public void anAbsentSectionKeepsTheDefaults() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setHousekeeping(null);

    properties.validateHousekeeping();

    assertEquals(LocalTime.of(4, 0), properties.getHousekeeping().getStart());
    assertEquals(LocalTime.of(5, 0), properties.getHousekeeping().getEnd());

  }

}
