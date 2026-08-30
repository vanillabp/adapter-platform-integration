package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Pins the election cache's defaults and its startup validation: what was
 * a fixed 10.000 entries / 1 hour became configurable WITHOUT changing what an
 * unconfigured application gets.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCachePropertiesTest {

  @Test
  @DisplayName("The defaults are 10.000 entries and one hour")
  public void defaultsArePinned() {

    final var properties = new WorkflowAdapterCacheProperties();

    assertEquals(10_000, properties.getMaxEntries());
    assertEquals(Duration.ofHours(1), properties.getTimeToLive());
    assertEquals(Duration.ofMinutes(5), properties.getEndedTimeToLive());
    assertFalse(
        properties.isReleaseOnWorkflowEnd(),
        "reporting the end of every workflow is not something an application pays for unasked");

  }

  @Test
  @DisplayName("An unconfigured section yields the defaults")
  public void unconfiguredSectionYieldsDefaults() {

    final var properties = new MigrationAdapterProperties();

    assertNotNull(properties.getWorkflowAdapterCache());
    assertEquals(10_000, properties.getWorkflowAdapterCache().getMaxEntries());

  }

  @Test
  @DisplayName("A cache holding no entry is rejected, naming the property")
  public void maxEntriesHasToBePositive() {

    final var message = assertThrows(
        IllegalStateException.class,
        () -> WorkflowAdapterCacheProperties
            .builder()
            .maxEntries(0)
            .build()
            .validate())
        .getMessage();

    assertTrue(
        message.contains(WorkflowAdapterCacheProperties.MAX_ENTRIES_PROPERTY),
        "the message has to name the property but got: "
            + message);
    assertTrue(
        message.contains("at least 1"),
        "the message has to name the fix but got: "
            + message);

  }

  @Test
  @DisplayName("An entry expiring immediately is rejected, naming the property")
  public void timeToLiveHasToBePositive() {

    final var message = assertThrows(
        IllegalStateException.class,
        () -> WorkflowAdapterCacheProperties
            .builder()
            .timeToLive(Duration.ZERO)
            .build()
            .validate())
        .getMessage();

    assertTrue(
        message.contains(WorkflowAdapterCacheProperties.TIME_TO_LIVE_PROPERTY),
        "the message has to name the property but got: "
            + message);

    assertThrows(
        IllegalStateException.class,
        () -> WorkflowAdapterCacheProperties
            .builder()
            .timeToLive(null)
            .build()
            .validate());
    assertThrows(
        IllegalStateException.class,
        () -> WorkflowAdapterCacheProperties
            .builder()
            .timeToLive(Duration.ofSeconds(-1))
            .build()
            .validate());

  }

  @Test
  @DisplayName("An ended workflow's entry expiring immediately is rejected, naming the property")
  public void endedTimeToLiveHasToBePositive() {

    final var message = assertThrows(
        IllegalStateException.class,
        () -> WorkflowAdapterCacheProperties
            .builder()
            .endedTimeToLive(Duration.ZERO)
            .build()
            .validate())
        .getMessage();

    assertTrue(
        message.contains(WorkflowAdapterCacheProperties.ENDED_TIME_TO_LIVE_PROPERTY),
        "the message has to name the property but got: "
            + message);

    assertThrows(
        IllegalStateException.class,
        () -> WorkflowAdapterCacheProperties
            .builder()
            .endedTimeToLive(null)
            .build()
            .validate());

  }

  @Test
  @DisplayName("An ended workflow outliving a running one is rejected, naming both properties")
  public void endedTimeToLiveHasToBeShorter() {

    final var message = assertThrows(
        IllegalStateException.class,
        () -> WorkflowAdapterCacheProperties
            .builder()
            .timeToLive(Duration.ofMinutes(1))
            .endedTimeToLive(Duration.ofMinutes(5))
            .build()
            .validate())
        .getMessage();

    assertTrue(
        message.contains(WorkflowAdapterCacheProperties.ENDED_TIME_TO_LIVE_PROPERTY) && message
            .contains(WorkflowAdapterCacheProperties.TIME_TO_LIVE_PROPERTY),
        "the message has to name both properties but got: "
            + message);

  }

  @Test
  @DisplayName("Configured bounds pass the validation")
  public void configuredBoundsAreAccepted() {

    WorkflowAdapterCacheProperties
        .builder()
        .maxEntries(100_000)
        .timeToLive(Duration.ofMinutes(30))
        .endedTimeToLive(Duration.ofMinutes(1))
        .releaseOnWorkflowEnd(true)
        .build()
        .validate();

  }

}
