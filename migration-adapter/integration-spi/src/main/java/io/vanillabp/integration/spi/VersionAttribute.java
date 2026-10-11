package io.vanillabp.integration.spi;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Whether a workflow aggregate has the attribute which a persistence layer increments on
 * each write. That attribute turns a second writer from a silent overwrite into an
 * exception. This is the implementation behind the default of
 * {@link AggregatePersistenceAware#detectsConcurrentModification()}.
 * <p>
 * It is one question, asked once, for every persistence layer VanillaBP supports. JPA calls
 * the annotation <code>Version</code>, Spring Data calls it <code>Version</code>, and Panache
 * on Quarkus does too. So the SIMPLE name decides. Writing the question per technology
 * would ask the same question several times, and the core must not depend on any of those
 * APIs to ask it.
 * <p>
 * A persistence layer which follows the same convention under another name is not
 * recognised. Neither is a store which notices a second writer in another way. Both
 * override {@link AggregatePersistenceAware#detectsConcurrentModification()} and answer
 * for themselves. If they do not, the cost is a warning where none was needed.
 */
public final class VersionAttribute {

  /**
   * The simple name every persistence layer VanillaBP supports gives that annotation.
   */
  private static final String VERSION_ANNOTATION = "Version";

  private VersionAttribute() {
    // utility class
  }

  /**
   * Whether the given class or one of its super classes declares a version attribute. It
   * looks at fields and at methods (JPA property access).
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return Whether a version attribute was found
   */
  public static boolean isDeclaredBy(
      final Class<?> workflowAggregateClass) {

    var type = workflowAggregateClass;
    while ((type != null) && (type != Object.class)) {
      final var annotated = Stream
          .concat(
              Arrays.stream(type.getDeclaredFields()),
              Arrays.stream(type.getDeclaredMethods()))
          .map(AnnotatedElement.class::cast)
          .toList();
      if (annotated
          .stream()
          .anyMatch(VersionAttribute::isVersionAnnotated)) {
        return true;
      }
      type = type.getSuperclass();
    }
    return false;

  }

  private static boolean isVersionAnnotated(
      final AnnotatedElement element) {

    return Arrays
        .stream(element.getAnnotations())
        .map(annotation -> annotation
            .annotationType()
            .getSimpleName())
        .anyMatch(VERSION_ANNOTATION::equals);

  }

}
