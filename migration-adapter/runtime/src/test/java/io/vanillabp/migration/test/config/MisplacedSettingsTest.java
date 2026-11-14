package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.extension.spi.settings.MisplacedSettings;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The refusal of a misplaced setting, called the way an extension calls it: from outside the
 * core, with keys of its own section. The tests in {@code MigrationAdapterPropertiesTest}
 * hold the messages of the core's own keys. This test holds that an extension gets the same
 * shape, so a developer reads one kind of message for both.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MisplacedSettingsTest {

  @Test
  @DisplayName("An extension's key below the level it is read at gets the shape of the core")
  public void anExtensionRefusesItsKeyInTheWordsOfTheCore() {

    final var refusal = MisplacedSettings.refuse(
        "The suspension check interval is read at the adapter",
        List.of(
            "vanillabp.workflow-modules.loans.adapters.saas.cockpit.suspension-check-interval",
            "vanillabp.workflow-modules.loans.workflows.Loan.adapters.saas.cockpit.suspension-check-interval"),
        "the adapter it is meant for",
        List.of("vanillabp.adapters.<adapter>.cockpit.suspension-check-interval: PT1M"),
        "One check runs for each adapter, so a workflow module cannot have an interval of its own.");

    assertEquals(
        """
            The suspension check interval is read at the adapter, but it is configured at:
              vanillabp.workflow-modules.loans.adapters.saas.cockpit.suspension-check-interval
              vanillabp.workflow-modules.loans.workflows.Loan.adapters.saas.cockpit.suspension-check-interval
            Move each of them to the adapter it is meant for:
              vanillabp.adapters.<adapter>.cockpit.suspension-check-interval: PT1M
            One check runs for each adapter, so a workflow module cannot have an interval of its own.""",
        refusal.getMessage());

  }

  @Test
  @DisplayName("The written keys are printed sorted, so every boot reads the same")
  public void theWrittenKeysArePrintedSorted() {

    final var refusal = MisplacedSettings.refuse(
        "The key is read at the adapter",
        List.of("vanillabp.workflow-modules.b.x.key", "vanillabp.workflow-modules.a.x.key"),
        "the adapter",
        List.of("vanillabp.adapters.<adapter>.x.key"),
        "Nothing else reads it.");

    assertEquals(
        """
            The key is read at the adapter, but it is configured at:
              vanillabp.workflow-modules.a.x.key
              vanillabp.workflow-modules.b.x.key
            Move each of them to the adapter:
              vanillabp.adapters.<adapter>.x.key
            Nothing else reads it.""",
        refusal.getMessage());

  }

}
