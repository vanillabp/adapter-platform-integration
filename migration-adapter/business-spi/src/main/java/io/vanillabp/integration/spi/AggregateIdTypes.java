package io.vanillabp.integration.spi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Determines the ID type of a workflow aggregate by reflection - the implementation
 * behind the default of {@link AggregatePersistenceAware#getAggregateIdType()}. The
 * class hierarchy is walked considering also <i>private</i> members:
 * <ol>
 * <li>a field annotated with a persistence ID annotation
 * ({@code jakarta.persistence.Id}, {@code jakarta.persistence.EmbeddedId} or
 * {@code org.bson.codecs.pojo.annotations.BsonId}),</li>
 * <li>secondarily a getter carrying such an annotation (JPA property access),</li>
 * <li>a field named <code>id</code>,</li>
 * <li>a getter named <code>getId</code>.</li>
 * </ol>
 * Annotations are matched by name so this module does not depend on any persistence
 * API. Persistence-framework-backed implementations of
 * {@link AggregatePersistenceAware} (e.g. based on Spring Data) override
 * {@link AggregatePersistenceAware#getAggregateIdType()} with the framework's
 * authoritative answer instead.
 */
public final class AggregateIdTypes {

  private static final Set<String> ID_ANNOTATIONS = Set.of(
      "jakarta.persistence.Id",
      "jakarta.persistence.EmbeddedId",
      "org.bson.codecs.pojo.annotations.BsonId");

  private AggregateIdTypes() {
    // utility class
  }

  /**
   * Determines the aggregate's ID type by reflection (see the class javadoc for the
   * precedence rules; private members are considered, too, since
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

}
