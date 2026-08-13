package io.vanillabp.integration.adapter.migration.workflowstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * The rules deciding the ID of a workflow aggregate nobody handed to VanillaBP. Lives
 * in the package of the class under test because those rules are internal: what the
 * application sees of them is documented and covered by the acceptance tests.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmsInitiatedStartIdTest {

  private static final Instant TRIGGER_TIME = Instant.parse("2026-08-12T04:00:00Z");

  private static final String INSTANCE_KEY = "2251799813685334";

  private static Object timerId(
      final Class<?> aggregateIdType) {

    return BpmsInitiatedStartId
        .derive(BpmsStartTrigger.Kind.TIMER, TRIGGER_TIME, null, aggregateIdType, Object.class)
        .orElse(null);

  }

  @Test
  @DisplayName("What the BPMS identifies the start by beats everything else")
  public void naturalIdentityWins() {

    assertEquals(
        INSTANCE_KEY,
        BpmsInitiatedStartId
            .derive(BpmsStartTrigger.Kind.TIMER, TRIGGER_TIME, INSTANCE_KEY, String.class, Object.class)
            .orElseThrow());
    assertEquals(
        Long.valueOf(INSTANCE_KEY),
        BpmsInitiatedStartId
            .derive(BpmsStartTrigger.Kind.SIGNAL, TRIGGER_TIME, INSTANCE_KEY, Long.class, Object.class)
            .orElseThrow());
    // an ID type which cannot carry it falls back to the trigger rules
    assertEquals(
        TRIGGER_TIME,
        BpmsInitiatedStartId
            .derive(BpmsStartTrigger.Kind.TIMER, TRIGGER_TIME, INSTANCE_KEY, Instant.class, Object.class)
            .orElseThrow());
    // ... and a non-numeric identity does not become a numeric ID
    assertTrue(
        BpmsInitiatedStartId
            .derive(BpmsStartTrigger.Kind.SIGNAL, TRIGGER_TIME, "not-a-number", Long.class, Object.class)
            .isEmpty());

  }

  @Test
  @DisplayName("A timer's ID is its trigger time, in whatever type the aggregate uses")
  public void triggerTimeIsConvertedToTheIdType() {

    assertEquals(TRIGGER_TIME.toString(), timerId(String.class));
    // no ID type reported: the persistence layer owns the serialized form
    assertEquals(TRIGGER_TIME.toString(), timerId(null));
    assertEquals(TRIGGER_TIME, timerId(Instant.class));
    assertEquals(TRIGGER_TIME.toEpochMilli(), timerId(Long.class));
    assertEquals(TRIGGER_TIME.toEpochMilli(), timerId(long.class));
    assertEquals(
        OffsetDateTime.ofInstant(TRIGGER_TIME, ZoneId.systemDefault()),
        timerId(OffsetDateTime.class));
    assertEquals(
        ZonedDateTime.ofInstant(TRIGGER_TIME, ZoneId.systemDefault()),
        timerId(ZonedDateTime.class));
    assertEquals(
        LocalDateTime.ofInstant(TRIGGER_TIME, ZoneId.systemDefault()),
        timerId(LocalDateTime.class));

  }

  @Test
  @DisplayName("An ID type which cannot carry the trigger time gets a generated ID or none at all")
  public void unfittingIdTypesFallBack() {

    // a UUID cannot be a point in time, but it can be generated
    assertInstanceOf(UUID.class, timerId(UUID.class));
    // a numeric ID without a natural value is left to the persistence layer
    assertTrue(
        BpmsInitiatedStartId
            .derive(BpmsStartTrigger.Kind.SIGNAL, TRIGGER_TIME, null, Long.class, Object.class)
            .isEmpty());
    assertTrue(
        BpmsInitiatedStartId
            .derive(BpmsStartTrigger.Kind.CONDITIONAL, TRIGGER_TIME, null, java.math.BigInteger.class, Object.class)
            .isEmpty());

  }

  @Test
  @DisplayName("Signals and conditions have no natural identity, so their IDs are generated and distinct")
  public void generatedIdsAreDistinct() {

    final var first = BpmsInitiatedStartId
        .derive(BpmsStartTrigger.Kind.SIGNAL, TRIGGER_TIME, null, String.class, Object.class)
        .orElseThrow();
    final var second = BpmsInitiatedStartId
        .derive(BpmsStartTrigger.Kind.CONDITIONAL, TRIGGER_TIME, null, String.class, Object.class)
        .orElseThrow();

    assertFalse(first.equals(second), "two starts by broadcast are two workflows");
    assertFalse(first.equals(TRIGGER_TIME.toString()), "the trigger time is not an identity here");

  }

}
