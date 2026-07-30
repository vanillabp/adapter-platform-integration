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
  @DisplayName("The outbox defaults are PT10S / PT30S / 10 / true / P7D")
  public void defaultsArePinned() {

    final var properties = new PhaseTwoOutboxProperties();

    assertEquals(Duration.ofSeconds(10), properties.getPollInterval());
    assertEquals(Duration.ofSeconds(30), properties.getAttemptFrequency());
    assertEquals(10, properties.getBlockAfterAttempts());
    assertTrue(properties.isCreateSchema());
    assertEquals(Duration.ofDays(7), properties.getRetention());

  }

  @Test
  @DisplayName("An unconfigured outbox section yields the defaults")
  public void unconfiguredSectionYieldsDefaults() {

    final var properties = new MigrationAdapterProperties();

    assertNotNull(properties.getOutbox());
    assertEquals(Duration.ofSeconds(10), properties.getOutbox().getPollInterval());

  }

}
