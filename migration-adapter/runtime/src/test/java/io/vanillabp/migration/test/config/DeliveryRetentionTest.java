package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * One number used to govern two windows, and after the outbound one ended with the
 * dispatch they stopped being the same kind of setting: on the outbox side the retention
 * decides how long a dispatched entry stays readable during support, on the delivery side
 * it decides whether a late redelivery runs the business code a second time. So there are
 * two properties now, and this pins how they relate.
 * <p>
 * The relation is the whole feature: an installation which never cared notices nothing,
 * one which lowered the old number keeps the behaviour it had, and one which wants the
 * correctness half longer than the operational half can say so. What it must not do is
 * change silently, which is what the startup message is for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeliveryRetentionTest {

  private ListAppender<ILoggingEvent> logWatcher;

  @BeforeEach
  public void watchTheLog() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).addAppender(logWatcher);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).detachAndStopAllAppenders();

  }

  private String loggedLines() {

    return logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(java.util.stream.Collectors.joining("\n"));

  }

  private static MigrationAdapterProperties properties(
      final Duration outboxRetention,
      final Duration deliveryRetention) {

    final var outbox = new PhaseTwoOutboxProperties();
    if (outboxRetention != null) {
      outbox.setRetention(outboxRetention);
    }
    final var delivery = new DeliveryProperties();
    delivery.setRetention(deliveryRetention);
    final var properties = new MigrationAdapterProperties();
    properties.setOutbox(outbox);
    properties.setDelivery(delivery);
    return properties;

  }

  @Test
  @DisplayName("Neither property set: seven days on both halves")
  public void bothHalvesDefaultToSevenDays() {

    final var properties = properties(null, null);

    assertEquals(Duration.ofDays(7), properties.getOutbox().getRetention());
    assertEquals(Duration.ofDays(7), properties.resolvedDeliveryRetention());
    assertEquals(
        PhaseTwoOutboxProperties.DEFAULT_RETENTION,
        properties.resolvedDeliveryRetention(),
        "the delivery default IS the outbox default, so a changed default moves both");

  }

  @Test
  @DisplayName("Only the outbox retention set: the delivery half follows it")
  public void theDeliveryHalfFollowsTheOutboxRetention() {

    // the upgrade case: this number governed both windows before, so an installation
    // which lowered it keeps the behaviour it had rather than silently gaining a longer
    // correctness window
    final var properties = properties(Duration.ofDays(2), null);

    assertEquals(Duration.ofDays(2), properties.resolvedDeliveryRetention());

  }

  @Test
  @DisplayName("Only the delivery retention set: the outbox keeps its default")
  public void theOutboxDoesNotFollowTheOtherWayRound()

  {

    final var properties = properties(null, Duration.ofDays(30));

    assertEquals(Duration.ofDays(30), properties.resolvedDeliveryRetention());
    assertEquals(Duration.ofDays(7), properties.getOutbox().getRetention());

  }

  @Test
  @DisplayName("Both set: each half is its own number")
  public void bothSetAreTwoNumbers() {

    final var properties = properties(Duration.ofDays(1), Duration.ofDays(30));

    assertEquals(Duration.ofDays(30), properties.resolvedDeliveryRetention());
    assertEquals(Duration.ofDays(1), properties.getOutbox().getRetention());

  }

  @Test
  @DisplayName("An absent outbox section still yields a delivery retention")
  public void anAbsentOutboxSectionDoesNotCostTheDefault() {

    // a binder mapping an absent section onto null must not end the boot, and it must
    // not answer null for a period the cleanup is constructed with
    final var properties = new MigrationAdapterProperties();
    properties.setOutbox(null);
    properties.setDelivery(null);

    assertEquals(Duration.ofDays(7), properties.resolvedDeliveryRetention());

  }

  @Test
  @DisplayName("The rule lives in one place, and the platforms call it")
  public void theResolutionIsOneFunction() {

    // the Quarkus delivery logs resolve their retention lazily and cannot ask a bound
    // core properties object, so they call this - which is why it is public and pinned
    final var delivery = new DeliveryProperties();
    assertEquals(
        Duration.ofDays(7),
        DeliveryProperties.resolveRetention(delivery, Duration.ofDays(7)));
    delivery.setRetention(Duration.ofDays(30));
    assertEquals(
        Duration.ofDays(30),
        DeliveryProperties.resolveRetention(delivery, Duration.ofDays(7)));
    assertEquals(
        Duration.ofDays(7),
        DeliveryProperties.resolveRetention(null, Duration.ofDays(7)),
        "an absent section means the outbox number");

  }

  @Test
  @DisplayName("An installation which moved only the outbox number is told that both moved")
  public void movingOnlyTheOutboxNumberIsReported() {

    validated(properties(Duration.ofDays(2), null));

    final var log = loggedLines();
    assertTrue(log.contains("vanillabp.outbox.retention"), log);
    assertTrue(log.contains("vanillabp.delivery.retention"), log);
    assertTrue(log.contains("PT48H"), log);
    // the sentence which makes the difference actionable
    assertTrue(log.contains("@WorkflowTask method a second time"), log);

  }

  @Test
  @DisplayName("An installation which set only the delivery number is told the outbox did not follow")
  public void settingOnlyTheDeliveryNumberIsReported() {

    validated(properties(null, Duration.ofDays(30)));

    final var log = loggedLines();
    assertTrue(log.contains("vanillabp.delivery.retention"), log);
    assertTrue(log.contains("P30D") || log.contains("PT720H"), log);
    assertTrue(log.contains("vanillabp.outbox.retention"), log);

  }

  @Test
  @DisplayName("Setting both, or neither, is worth no word")
  public void agreementIsSilent() {

    validated(properties(Duration.ofDays(1), Duration.ofDays(30)));
    validated(properties(null, null));

    assertTrue(
        !loggedLines().contains("retention"),
        "an application which said what it means is not lectured about it: "
            + loggedLines());

  }

  /**
   * Runs the startup validation of an otherwise minimal application, which is where the
   * message about the two retentions is written.
   */
  private static void validated(
      final MigrationAdapterProperties properties) {

    properties
        .setAdapters(
            java.util.Map
                .of("dummy", io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties
                    .ofType("dummy")));
    properties
        .setWorkflowModules(
            java.util.Map
                .of(
                    "test-module",
                    io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties
                        .builder()
                        .workflowModuleId("test-module")
                        .build()));
    properties.setPrioritizedAdapters(java.util.List.of("dummy"));
    properties.validateProperties(java.util.List.of("dummy"), java.util.List.of("test-module"));

  }

}
