package io.vanillabp.migration.test.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an adapter reports stands in the same block as what the platform reports.
 * <p>
 * An adapter holds {@link StartupReport}, which is the reporting half of the collection
 * and nothing else. This is what that half is worth: a finding handed over through it is
 * folded, grouped and counted like any other, and the developer reads one block instead of
 * one per artifact their application is built from.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnAdapterReportsIntoTheSameBoxTest {

  /**
   * An adapter holding nothing but the reporting half - the way both platform
   * integrations hand it over.
   */
  private static void theAdapterFoundSomething(
      final StartupReport report) {

    report
        .warn(
            StartupTopic.CONFIGURATION,
            "camunda8 adapter 'cloud'",
            "The request timeout is half a second. Raise it to at least PT1S.");

  }

  @Test
  @DisplayName("A finding of an adapter stands in the block beside the platform's own")
  public void anAdaptersFindingIsAnEntryOfTheBlock() {

    final var properties = new MigrationAdapterProperties();

    properties
        .startupFindings()
        .warn(
            StartupTopic.CONFIGURATION,
            "vanillabp.outbox.housekeeping",
            "The window runs at four UTC.");
    theAdapterFoundSomething(properties.startupFindings());

    final var box = properties.startupFindings().theBox();

    assertTrue(box.contains("CONFIGURATION (2)"), box);
    assertTrue(box.contains("camunda8 adapter 'cloud'"), box);
    assertTrue(box.contains("The window runs at four UTC."), box);

  }

  @Test
  @DisplayName("Two adapter instances finding the same thing are one entry naming both")
  public void oneFindingOfTwoAdapterIdsIsOneEntry() {

    final var findings = new MigrationAdapterProperties().startupFindings();

    for (final var adapterId : new String[]{
        "cloud", "on-premise"
    }) {
      findings
          .warn(
              StartupTopic.CONFIGURATION,
              "camunda8 adapter '%s'".formatted(adapterId),
              "The request timeout is half a second. Raise it to at least PT1S.");
    }

    assertEquals(2, findings.findings().size(), "both adapter instances reported");
    final var box = findings.theBox();
    assertTrue(box.contains("CONFIGURATION (1)"), box);
    assertTrue(box.contains("camunda8 adapter 'cloud', camunda8 adapter 'on-premise'"), box);

  }

  @Test
  @DisplayName("An adapter which refuses the start has its reason thrown with the others")
  public void anAdaptersRefusalIsThrownWithTheOthers() {

    final var findings = new MigrationAdapterProperties().startupFindings();

    findings
        .refuse(
            StartupTopic.CONFIGURATION,
            "camunda8 adapter 'cloud'",
            "No cluster address is configured.");
    findings.refuse(StartupTopic.CODE, "class 'io.example.Loans'", "Two methods serve version 3.");

    final var refusal = findings.theRefusal();

    assertTrue(refusal.getMessage().contains("2 things have to change"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("No cluster address is configured."), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("Two methods serve version 3."), refusal.getMessage());

  }

}
