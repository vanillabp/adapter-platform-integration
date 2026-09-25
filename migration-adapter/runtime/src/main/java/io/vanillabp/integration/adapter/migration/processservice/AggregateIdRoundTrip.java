package io.vanillabp.integration.adapter.migration.processservice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

/**
 * Round-trip handling of workflow-aggregate IDs crossing the phase-two outbox: the ID
 * is serialized as a String by
 * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementations and
 * converted back to the aggregate's ID type at dispatch time. The ID type comes from
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware#getAggregateIdType()}
 * - if it is <code>null</code> (custom persistence owning the serialized form),
 * nothing is validated and the String is passed through unchanged.
 * <p>
 * The supported ID types are an explicit, platform-independent allow-list: every
 * listed type converts String → ID → String losslessly. This single definition
 * replaces the former platform-specific pair (a Spring
 * <code>ConversionService</code>-based check and a Quarkus allow-list).
 */
@Slf4j
public final class AggregateIdRoundTrip {

  /**
   * ID types which round-trip losslessly through the phase-two outbox's String
   * serialization (see {@link #convert(String, Class)}).
   */
  public static final Set<String> SUPPORTED_ID_TYPES = Set.of(
      "java.lang.String",
      "java.lang.Long", "long",
      "java.lang.Integer", "int",
      "java.lang.Short", "short",
      "java.lang.Byte", "byte",
      "java.lang.Double", "double",
      "java.lang.Float", "float",
      "java.lang.Boolean", "boolean",
      "java.math.BigInteger",
      "java.math.BigDecimal",
      "java.util.UUID");

  private AggregateIdRoundTrip() {
    // utility class
  }

  /**
   * Validates AT STARTUP that the aggregate's ID type converts String → ID → String
   * losslessly: the ID crosses the phase-two outbox serialized as a String, so an
   * unconvertible type would corrupt the dispatch silently. A <code>null</code> ID
   * type is fine (custom persistence - the serialized form is the custom layer's
   * responsibility, nothing is validated).
   *
   * @param workflowAggregateClass The aggregate's class (used in the message)
   * @param aggregateIdType The aggregate's ID type or <code>null</code>
   * @throws IllegalStateException If the ID type is known but not convertible,
   *           naming the aggregate class and the supported ID types
   */
  public static void validateIdTypeConvertible(
      final Class<?> workflowAggregateClass,
      final Class<?> aggregateIdType) {

    if ((aggregateIdType == null) || SUPPORTED_ID_TYPES.contains(aggregateIdType.getName())) {
      return;
    }
    throw new IllegalStateException(
        """
            The ID of workflow aggregate '%s' is of type '%s', which cannot be converted from/to \
            String! The ID crosses the phase-two outbox serialized as a String and must round-trip \
            losslessly. Use one of the supported ID types (String, Long, Integer, Short, Byte, \
            Double, Float, Boolean, BigInteger, BigDecimal, UUID) or let getAggregateIdType of your \
            custom AggregatePersistenceAware implementation return null if your persistence layer \
            owns the serialized form."""
            .formatted(workflowAggregateClass.getName(), aggregateIdType.getName()));

  }

  /**
   * Converts the serialized aggregate ID to the given target type. Unsupported types
   * and a <code>null</code> target type are passed through as strings.
   *
   * @param serializedAggregateId The aggregate ID in serialized form
   * @param aggregateIdType The type the aggregate ID should be converted to
   * @return The converted aggregate ID
   */
  public static Object convert(
      final String serializedAggregateId,
      final Class<?> aggregateIdType) {

    if ((serializedAggregateId == null) || (aggregateIdType == null)) {
      return serializedAggregateId;
    }
    final var converted = convertIfItFits(serializedAggregateId, aggregateIdType);
    if (converted.isPresent()) {
      return converted.get();
    }
    if (SUPPORTED_ID_TYPES.contains(aggregateIdType.getName())) {
      log.warn(
          "Could not convert workflow-aggregate ID '{}' to type '{}' - passing it through as a string!",
          serializedAggregateId,
          aggregateIdType.getName());
    } else {
      log.debug(
          "Unsupported workflow-aggregate ID type '{}' - passing the serialized ID through as a string",
          aggregateIdType.getName());
    }
    return serializedAggregateId;

  }

  /**
   * Converts the serialized aggregate ID to the given target type, and answers EMPTY
   * where the value does not fit that type - a text where a number or a UUID is
   * expected, or a type this class does not know.
   * <p>
   * This is the answer a caller needs which has a string and a question rather than a
   * string it already knows to be an ID: a business key somebody else chose is looked up
   * as an aggregate's ID, and where it cannot be one, the lookup has to end with "there
   * is none" instead of a failing query or an invented ID.
   *
   * @param serializedAggregateId The aggregate ID in serialized form
   * @param aggregateIdType The type the aggregate ID should be converted to, or
   *          <code>null</code> if the persistence layer owns the serialized form
   * @return The converted ID, or {@link Optional#empty()} if the value cannot be one
   */
  public static Optional<Object> convertIfItFits(
      final String serializedAggregateId,
      final Class<?> aggregateIdType) {

    if (serializedAggregateId == null) {
      return Optional.empty();
    }
    if (aggregateIdType == null) {
      // the persistence layer owns the serialized form, so the string is the ID
      return Optional.of(serializedAggregateId);
    }
    try {
      return switch (aggregateIdType.getName()) {
        case "java.lang.String" -> Optional.of(serializedAggregateId);
        case "java.lang.Long", "long" -> Optional.of(Long.valueOf(serializedAggregateId));
        case "java.lang.Integer", "int" -> Optional.of(Integer.valueOf(serializedAggregateId));
        case "java.lang.Short", "short" -> Optional.of(Short.valueOf(serializedAggregateId));
        case "java.lang.Byte", "byte" -> Optional.of(Byte.valueOf(serializedAggregateId));
        case "java.lang.Double", "double" -> Optional.of(Double.valueOf(serializedAggregateId));
        case "java.lang.Float", "float" -> Optional.of(Float.valueOf(serializedAggregateId));
        case "java.lang.Boolean", "boolean" -> Optional.of(Boolean.valueOf(serializedAggregateId));
        case "java.math.BigInteger" -> Optional.of(new BigInteger(serializedAggregateId));
        case "java.math.BigDecimal" -> Optional.of(new BigDecimal(serializedAggregateId));
        case "java.util.UUID" -> Optional.of(UUID.fromString(serializedAggregateId));
        default -> Optional.empty();
      };
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }

  }

}
