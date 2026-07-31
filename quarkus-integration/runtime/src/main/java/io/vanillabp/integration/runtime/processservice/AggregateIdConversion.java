package io.vanillabp.integration.runtime.processservice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

/**
 * Conversion of workflow-aggregate IDs serialized as strings by a
 * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} back to the
 * aggregate's ID type. The ID type is determined once per aggregate class by
 * reflection (the platform knows the persistence layer), walking the class
 * hierarchy and considering also <i>private</i> members:
 * <ol>
 * <li>a field annotated with a persistence ID annotation
 * ({@code jakarta.persistence.Id}, {@code jakarta.persistence.EmbeddedId} or
 * {@code org.bson.codecs.pojo.annotations.BsonId}),</li>
 * <li>secondarily a getter carrying such an annotation (JPA property access),</li>
 * <li>a field named <code>id</code>,</li>
 * <li>a getter named <code>getId</code>.</li>
 * </ol>
 * Annotations are matched by name so this module does not depend on any
 * persistence API.
 * <p>
 * If the ID type cannot be determined or is not a supported simple type, the
 * serialized String is passed through unchanged - the custom persistence layer is
 * then responsible for handling the serialized form.
 */
@Slf4j
public final class AggregateIdConversion {

  private static final Set<String> ID_ANNOTATIONS = Set.of(
      "jakarta.persistence.Id",
      "jakarta.persistence.EmbeddedId",
      "org.bson.codecs.pojo.annotations.BsonId");

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

  /**
   * Validates AT STARTUP that the aggregate's ID type converts String → ID → String
   * losslessly: the ID crosses the phase-two outbox serialized as a String, so an
   * unconvertible type would corrupt the dispatch silently. If the ID type cannot
   * be determined at all (custom persistence), the serialized form is the custom
   * layer's responsibility and nothing is validated.
   *
   * @param workflowAggregateClass The aggregate's class
   * @throws IllegalStateException If the ID type is known but not convertible,
   *           naming the aggregate class and the supported ID types
   */
  public static void validateIdTypeConvertible(
      final Class<?> workflowAggregateClass) {

    determineIdType(workflowAggregateClass)
        .filter(idType -> !SUPPORTED_ID_TYPES.contains(idType.getName()))
        .ifPresent(idType -> {
          throw new IllegalStateException(
              """
                  The ID of workflow aggregate '%s' is of type '%s', which cannot be converted from/to                   String! The ID crosses the phase-two outbox serialized as a String and must round-trip                   losslessly. Use one of the supported ID types (String, Long, Integer, Short, Byte,                   Double, Float, Boolean, BigInteger, BigDecimal, UUID) or provide a custom                   AggregatePersistenceAware implementation handling the serialized form."""
                  .formatted(workflowAggregateClass.getName(), idType.getName()));
        });

  }

  private AggregateIdConversion() {
    // utility class
  }

  /**
   * Determines the aggregate's ID type by reflection (see the class javadoc for
   * the precedence rules; private members are considered, too, since
   * {@link Class#getDeclaredFields()}/{@link Class#getDeclaredMethods()} include
   * them).
   *
   * @param workflowAggregateClass The aggregate's class
   * @return The ID type or {@link Optional#empty()} if it cannot be determined
   */
  public static Optional<Class<?>> determineIdType(
      final Class<?> workflowAggregateClass) {

    Method annotatedGetter = null;
    Field idNamedField = null;
    Method idNamedGetter = null;
    for (var current = workflowAggregateClass; (current != null) && (current != Object.class); current = current
        .getSuperclass()) {
      for (final var field : current.getDeclaredFields()) {
        if (isIdAnnotated(field.getAnnotations())) {
          // an annotated field is the most specific evidence - return immediately
          return Optional.of(field.getType());
        }
        if ((idNamedField == null) && field.getName().equals("id")) {
          idNamedField = field;
        }
      }
      for (final var method : current.getDeclaredMethods()) {
        if (!isGetter(method)) {
          continue;
        }
        if ((annotatedGetter == null) && isIdAnnotated(method.getAnnotations())) {
          annotatedGetter = method;
        }
        if ((idNamedGetter == null) && method.getName().equals("getId")) {
          idNamedGetter = method;
        }
      }
    }
    if (annotatedGetter != null) {
      return Optional.of(annotatedGetter.getReturnType());
    }
    if (idNamedField != null) {
      return Optional.of(idNamedField.getType());
    }
    return Optional.ofNullable(idNamedGetter).map(Method::getReturnType);

  }

  private static boolean isIdAnnotated(
      final java.lang.annotation.Annotation[] annotations) {

    return Arrays
        .stream(annotations)
        .anyMatch(annotation -> ID_ANNOTATIONS.contains(annotation.annotationType().getName()));

  }

  private static boolean isGetter(
      final Method method) {

    return (method.getParameterCount() == 0) && (method.getReturnType() != void.class) && (method.getName()
        .startsWith("get") || method.getName().startsWith("is"));

  }

  /**
   * Converts the serialized aggregate ID to the given target type. Unsupported types
   * are passed through as strings.
   *
   * @param serializedAggregateId The aggregate ID in serialized form
   * @param aggregateIdType The type the aggregate ID should be converted to
   * @return The converted aggregate ID
   */
  public static Object convert(
      final String serializedAggregateId,
      final Class<?> aggregateIdType) {

    if (serializedAggregateId == null) {
      return null;
    }
    try {
      return switch (aggregateIdType.getName()) {
        case "java.lang.String" -> serializedAggregateId;
        case "java.lang.Long", "long" -> Long.valueOf(serializedAggregateId);
        case "java.lang.Integer", "int" -> Integer.valueOf(serializedAggregateId);
        case "java.lang.Short", "short" -> Short.valueOf(serializedAggregateId);
        case "java.lang.Byte", "byte" -> Byte.valueOf(serializedAggregateId);
        case "java.lang.Double", "double" -> Double.valueOf(serializedAggregateId);
        case "java.lang.Float", "float" -> Float.valueOf(serializedAggregateId);
        case "java.lang.Boolean", "boolean" -> Boolean.valueOf(serializedAggregateId);
        case "java.math.BigInteger" -> new BigInteger(serializedAggregateId);
        case "java.math.BigDecimal" -> new BigDecimal(serializedAggregateId);
        case "java.util.UUID" -> UUID.fromString(serializedAggregateId);
        default -> {
          log.debug(
              "Unsupported workflow-aggregate ID type '{}' - passing the serialized ID through as a string",
              aggregateIdType.getName());
          yield serializedAggregateId;
        }
      };
    } catch (IllegalArgumentException e) {
      log.warn(
          "Could not convert workflow-aggregate ID '{}' to type '{}' - passing it through as a string!",
          serializedAggregateId,
          aggregateIdType.getName(),
          e);
      return serializedAggregateId;
    }

  }

}
