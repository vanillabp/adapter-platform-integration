package io.vanillabp.integration.adapter.migration.workflowstart;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Derives the ID of the workflow aggregate of a workflow the BPMS started on its
 * own. Nobody handed VanillaBP an aggregate, so the ID has to come from the trigger
 * itself.
 * <p>
 * <strong>What the BPMS itself identifies the start by wins.</strong> A remote BPMS
 * reports its process instance key, and using it as the aggregate's ID is what makes
 * a redelivered notification harmless: the aggregate is found, not built again.
 * <p>
 * <strong>Otherwise a timer's ID is its trigger time.</strong> That is not cosmetic: a cyclic
 * timer firing the same instant twice - a retried listener job, an engine
 * transaction replayed after a crash - derives the same ID, finds the aggregate
 * already there and creates nothing twice. The time is converted into whatever type
 * the aggregate's ID attribute has.
 * <p>
 * Signals and conditions have no such natural identity: two workflows started by the
 * same broadcast are two workflows, so their IDs are generated. Where no ID can be
 * derived at all - a numeric ID attribute without a natural value - none is assigned
 * and the persistence layer generates one while saving, which is how an application
 * with <code>&#64;GeneratedValue</code> IDs works anyway. An application wanting a
 * business ID provides a <code>&#64;WorkflowStartedByBpms</code> method and returns
 * an aggregate carrying it.
 */
final class BpmsInitiatedStartId {

  private static final Logger log = LoggerFactory.getLogger(BpmsInitiatedStartId.class);

  private BpmsInitiatedStartId() {
  }

  /**
   * @param kind The kind of start event which fired
   * @param triggerTime When it fired
   * @param naturalIdentity What the BPMS identifies this start by, or
   *          <code>null</code>
   * @param aggregateIdType The type of the aggregate's ID attribute, or
   *          <code>null</code> if the persistence layer does not tell
   * @param workflowAggregateClass The aggregate class, named by log messages
   * @return The ID to assign in the aggregate's ID type, or
   *         {@link Optional#empty()} to let the persistence layer assign one
   */
  static Optional<Object> derive(
      final BpmsStartTrigger.Kind kind,
      final Instant triggerTime,
      final String naturalIdentity,
      final Class<?> aggregateIdType,
      final Class<?> workflowAggregateClass) {

    if (naturalIdentity != null) {
      final var fromBpms = ofNaturalIdentity(naturalIdentity, aggregateIdType);
      if (fromBpms.isPresent()) {
        return fromBpms;
      }
      log
          .debug(
              """
                  The BPMS identifies the start of a workflow of '{}' as '{}', but the aggregate's ID \
                  attribute is of type '{}' and cannot carry it - the ID is derived from the trigger \
                  instead, so a repeated notification may build a second aggregate.""",
              workflowAggregateClass.getName(),
              naturalIdentity,
              aggregateIdType == null ? "unknown" : aggregateIdType.getName());
    }

    if (kind == BpmsStartTrigger.Kind.TIMER) {
      final var fromTriggerTime = ofTriggerTime(triggerTime, aggregateIdType);
      if (fromTriggerTime.isPresent()) {
        return fromTriggerTime;
      }
      log
          .debug(
              """
                  The ID attribute of the workflow aggregate '{}' is of type '{}' and cannot carry the \
                  trigger time - the ID of this timer-started workflow is generated instead, so a \
                  repeated notification for the same trigger time cannot be recognized.""",
              workflowAggregateClass.getName(),
              aggregateIdType == null ? "unknown" : aggregateIdType.getName());
    }
    return generate(aggregateIdType);

  }

  private static Optional<Object> ofNaturalIdentity(
      final String naturalIdentity,
      final Class<?> aggregateIdType) {

    if ((aggregateIdType == null) || aggregateIdType.equals(String.class)) {
      return Optional.of(naturalIdentity);
    }
    if (aggregateIdType.equals(Long.class) || aggregateIdType.equals(long.class)) {
      try {
        return Optional.of(Long.valueOf(naturalIdentity));
      } catch (final NumberFormatException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();

  }

  private static Optional<Object> ofTriggerTime(
      final Instant triggerTime,
      final Class<?> aggregateIdType) {

    if ((aggregateIdType == null) || aggregateIdType.equals(String.class)) {
      // no ID type reported means the persistence layer owns the serialized form -
      // the ISO-8601 representation is what travels everywhere else, too
      return Optional.of(triggerTime.toString());
    }
    if (aggregateIdType.equals(Instant.class)) {
      return Optional.of(triggerTime);
    }
    if (aggregateIdType.equals(OffsetDateTime.class)) {
      return Optional.of(OffsetDateTime.ofInstant(triggerTime, ZoneId.systemDefault()));
    }
    if (aggregateIdType.equals(ZonedDateTime.class)) {
      return Optional.of(ZonedDateTime.ofInstant(triggerTime, ZoneId.systemDefault()));
    }
    if (aggregateIdType.equals(LocalDateTime.class)) {
      return Optional.of(LocalDateTime.ofInstant(triggerTime, ZoneId.systemDefault()));
    }
    if (aggregateIdType.equals(Long.class) || aggregateIdType.equals(long.class)) {
      return Optional.of(triggerTime.toEpochMilli());
    }
    return Optional.empty();

  }

  private static Optional<Object> generate(
      final Class<?> aggregateIdType) {

    if ((aggregateIdType == null) || aggregateIdType.equals(String.class)) {
      return Optional.of(UUID.randomUUID().toString());
    }
    if (aggregateIdType.equals(UUID.class)) {
      return Optional.of(UUID.randomUUID());
    }
    // numeric and everything else: let the persistence layer assign the ID while
    // saving instead of inventing one which might collide
    return Optional.empty();

  }

}
