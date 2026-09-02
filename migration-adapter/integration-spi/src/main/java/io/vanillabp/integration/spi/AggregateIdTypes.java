package io.vanillabp.integration.spi;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Determines the ID of a workflow aggregate by reflection: its type (the
 * implementation behind the default of
 * {@link AggregatePersistenceAware#getAggregateIdType()}), its property name and its
 * value. The class hierarchy is walked considering also <i>private</i> members:
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

    return determineIdMember(workflowAggregateClass)
        .map(member -> member instanceof Field field
            ? field.getType()
            : ((Method) member).getReturnType());

  }

  /**
   * Determines the name of the aggregate's ID property using the same precedence
   * rules as {@link #determineIdType(Class)} - the implementation behind
   * {@link AggregatePersistenceAware#getAggregateIdName()} of the platform-provided
   * persistence implementations. A getter contributes its property name
   * ({@code getLoanRequestId} &rarr; {@code loanRequestId}).
   *
   * @param workflowAggregateClass The aggregate's class
   * @return The name of the ID property or {@link Optional#empty()} if it cannot be
   *         determined
   */
  public static Optional<String> determineIdName(
      final Class<?> workflowAggregateClass) {

    return determineIdMember(workflowAggregateClass)
        .map(member -> member instanceof Field field
            ? field.getName()
            : propertyNameOf((Method) member));

  }

  /**
   * Reads the aggregate's ID using the same precedence rules as
   * {@link #determineIdType(Class)} - the implementation behind
   * {@link AggregatePersistenceAware#getAggregateId(Object)} of the platform-provided
   * persistence implementations. Fields are read directly (also private ones), which
   * is what JPA and MongoDB codecs do as well.
   *
   * @param workflowAggregate The aggregate to read the ID from
   * @return The ID, which may be <code>null</code> for an aggregate not persisted yet
   *         (generated IDs)
   * @throws IllegalStateException If the aggregate has no property qualifying as its ID
   */
  public static Object readId(
      final Object workflowAggregate) {

    final var member = determineIdMember(workflowAggregate.getClass())
        .orElseThrow(() -> new IllegalStateException(
            """
                No ID property found for workflow aggregate '%s'! Annotate it (e.g. \
                jakarta.persistence.Id or org.bson.codecs.pojo.annotations.BsonId), name it \
                'id', or provide your own io.vanillabp.integration.spi.AggregatePersistenceAware \
                implementation for this aggregate."""
                .formatted(workflowAggregate.getClass().getName())));
    try {
      if (member instanceof Field field) {
        field.setAccessible(true);
        return field.get(workflowAggregate);
      }
      final var getter = (Method) member;
      getter.setAccessible(true);
      return getter.invoke(workflowAggregate);
    } catch (final IllegalStateException e) {
      throw e;
    } catch (final Exception e) {
      throw new IllegalStateException(
          "Could not read the ID of workflow aggregate '%s'!"
              .formatted(workflowAggregate.getClass().getName()), e);
    }

  }

  /**
   * The field or getter holding the aggregate's ID, see the class javadoc for the
   * precedence rules.
   */
  private static Optional<AccessibleObject> determineIdMember(
      final Class<?> workflowAggregateClass) {

    Method annotatedGetter = null;
    Field idNamedField = null;
    Method idNamedGetter = null;
    for (var current = workflowAggregateClass; (current != null) && (current != Object.class); current = current
        .getSuperclass()) {
      for (final var field : current.getDeclaredFields()) {
        if (isIdAnnotated(field.getAnnotations())) {
          // an annotated field is the most specific evidence - return immediately
          return Optional.of(field);
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
      return Optional.of(annotatedGetter);
    }
    if (idNamedField != null) {
      return Optional.of(idNamedField);
    }
    return Optional.ofNullable(idNamedGetter);

  }

  /**
   * The JavaBean property name of a getter: {@code getId} &rarr; {@code id},
   * {@code isDone} &rarr; {@code done}.
   */
  private static String propertyNameOf(
      final Method getter) {

    final var name = getter.getName();
    final var withoutPrefix = name.startsWith("is")
        ? name.substring(2)
        : name.substring(3);
    if (withoutPrefix.isEmpty()) {
      return name;
    }
    return Character.toLowerCase(withoutPrefix.charAt(0)) + withoutPrefix.substring(1);

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
