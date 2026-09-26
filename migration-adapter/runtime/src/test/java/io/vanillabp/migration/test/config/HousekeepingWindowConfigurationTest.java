package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.HousekeepingProperties;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the housekeeping window accepts, what it refuses and what it only warns about.
 * <p>
 * The case a developer never meets is the zone: a container stands on UTC unless somebody
 * sets its zone, so "four in the morning" becomes four UTC, which in most places is the
 * middle of the working day. It is noticed when the application is installed on a server,
 * which is why the message names two ways out and both of them go through an environment
 * variable. It is a warning and not a refusal, because UTC is the normal case for a
 * container and a refused start would cost the deployment - see decision 91 in the
 * repository's DECISIONS.md.
 * <p>
 * The warning does not go into the log where it is found. It is left with the findings of
 * the start and reaches the reader in the box at the end of it (decision &lt;pending:
 * 579&gt;), which is why these tests read the findings rather than a log appender.
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

  /**
   * Where the validation leaves what it noticed, one per test.
   */
  private StartupFindings findings;

  @BeforeEach
  public void startWithAnEmptyCollection() {

    findings = new StartupFindings();

  }

  /**
   * What the validation noticed, as one text - the same thing the box shows, without its
   * frame.
   */
  private String whatWasNoticed() {

    return findings
        .findings()
        .stream()
        .map(StartupFindings.Finding::message)
        .collect(Collectors.joining("\n"));

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
        () -> outboxWith(housekeeping).validateHousekeeping(findings));

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

    assertDoesNotThrow(() -> outboxWith(housekeeping).validateHousekeeping(findings));

  }

  @Test
  @DisplayName("A zone nobody knows is refused, naming the key and how a zone is written")
  public void aZoneNobodyKnowsIsRefused() {

    final var housekeeping = new HousekeepingProperties();
    housekeeping.setZone("Middle/Earth");

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> outboxWith(housekeeping).validateHousekeeping(findings));

    final var message = refused.getMessage();
    assertTrue(message.contains(HousekeepingProperties.ZONE_PROPERTY), message);
    assertTrue(message.contains("Middle/Earth"), message);
    // the message shows how a zone is written, so a reader needs no other source
    assertTrue(message.contains("Europe/Vienna"), message);

  }

  @Test
  @DisplayName("A JVM on UTC without a configured zone is warned, and the message names both ways out")
  public void aJvmOnUtcWithoutAZoneIsWarned() {

    TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));

    outboxWith(new HousekeepingProperties()).validateHousekeeping(findings);

    final var warning = whatWasNoticed();
    // what is happening, and why
    assertTrue(warning.contains("middle of the working day"), warning);
    // and the two ways out, neither of which needs a new build
    assertTrue(warning.contains("TZ=Europe/Vienna"), warning);
    assertTrue(warning.contains(HousekeepingProperties.ZONE_ENVIRONMENT_VARIABLE), warning);
    assertTrue(warning.contains(HousekeepingProperties.ZONE_PROPERTY), warning);
    // and the way to keep UTC on purpose
    assertTrue(warning.contains("Write 'UTC' there"), warning);

  }

  @Test
  @DisplayName("It stays a warning: the application which reads it starts")
  public void theApplicationStartsAnyway() {

    TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));

    // UTC is what a container ships with, so refusing such a start would invent a
    // precondition rather than uncover a mistake
    assertDoesNotThrow(() -> outboxWith(new HousekeepingProperties()).validateHousekeeping(findings));

  }

  @Test
  @DisplayName("The warning names the hours the window really runs at")
  public void theWarningNamesTheHours() {

    TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));
    final var housekeeping = new HousekeepingProperties();
    housekeeping.setStart(LocalTime.of(23, 30));
    housekeeping.setEnd(LocalTime.of(1, 15));

    outboxWith(housekeeping).validateHousekeeping(findings);

    final var warning = whatWasNoticed();
    assertTrue(warning.contains("23:30"), warning);
    assertTrue(warning.contains("01:15"), warning);

  }

  @Test
  @DisplayName("Every spelling of UTC is warned about, not only the one the JVM happens to use")
  public void everySpellingOfUtcIsWarnedAbout() {

    for (final var spelling : new String[]{
        "UTC", "Etc/UTC", "GMT", "Z", "Etc/GMT"
    }) {
      findings = new StartupFindings();
      TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(spelling)));

      outboxWith(new HousekeepingProperties()).validateHousekeeping(findings);

      assertTrue(
          whatWasNoticed().contains("middle of the working day"),
          "a JVM on '%s' means UTC as much as the others do".formatted(spelling));
    }

  }

  @Test
  @DisplayName("A JVM on UTC says nothing where UTC was configured on purpose")
  public void utcOnPurposeSaysNothing() {

    TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));
    final var housekeeping = new HousekeepingProperties();
    housekeeping.setZone("UTC");

    assertDoesNotThrow(() -> outboxWith(housekeeping).validateHousekeeping(findings));

    assertEquals("", whatWasNoticed(), "somebody who wrote UTC down is not told about UTC");

  }

  @Test
  @DisplayName("A JVM which stands on a real zone is told nothing at all")
  public void aJvmWithAZoneIsToldNothing() {

    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Vienna"));

    assertDoesNotThrow(() -> outboxWith(new HousekeepingProperties()).validateHousekeeping(findings));

    assertEquals("", whatWasNoticed());

  }

  @Test
  @DisplayName("The zone is a warning of the configuration, and it names the section it is about")
  public void theWarningIsFiledUnderTheConfiguration() {

    TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));

    outboxWith(new HousekeepingProperties()).validateHousekeeping(findings);

    assertEquals(1, findings.findings().size(), whatWasNoticed());
    final var finding = findings.findings().get(0);
    assertEquals(StartupFindings.Severity.WARNING, finding.severity());
    // the fix is a line of configuration or an environment variable, so the box sends the
    // reader to the configuration
    assertEquals(StartupTopic.CONFIGURATION, finding.topic());
    assertEquals(HousekeepingProperties.HOUSEKEEPING_PREFIX, finding.scope());

  }

  @Test
  @DisplayName("A binder which mapped the absent section onto nothing costs no defaults")
  public void anAbsentSectionKeepsTheDefaults() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setHousekeeping(null);

    properties.validateHousekeeping(findings);

    assertEquals(LocalTime.of(4, 0), properties.getHousekeeping().getStart());
    assertEquals(LocalTime.of(5, 0), properties.getHousekeeping().getEnd());

  }

}
