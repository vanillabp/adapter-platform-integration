package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The refusal with which both platforms answer a <code>&#64;WorkflowService</code> which does
 * not sit on the class that serves the tasks. That is the case on an interface which the
 * class implements, or on an annotation of the application's own which carries
 * <code>&#64;WorkflowService</code>.
 * <p>
 * <code>&#64;Inherited</code> covers neither of the two. A subclass of an annotated class IS a
 * workflow service, and it stays one on both platforms. The handler methods are read from
 * it. It is the class which is named when a BPMN process ID follows the class name. And its
 * archive decides the workflow module. An implementing class is not, because Java does not
 * inherit method annotations from an interface. So the <code>&#64;WorkflowTask</code>
 * methods of the interface and of the implementation are two different sets, and the
 * platforms do not read the same one. Spring Boot reads the bean's class, whose overriding
 * methods have no annotation. Quarkus reads the annotated type of its index, which is the
 * interface, and serves the methods declared there on an instance of the implementation.
 * The refusal avoids one source file with two behaviours.
 * <p>
 * The text lives in the core because both platform integrations say it. Each of them finds
 * the declaration its own way: among the bean definitions on Spring Boot, in the Jandex index
 * while a Quarkus application is built.
 */
public final class WorkflowServiceBelongsOnAClass {

  /**
   * What to do instead, the same for both shapes of the defect. The annotation goes where the
   * handler methods are. A declaration meant for several classes goes onto their common
   * superclass.
   */
  private static final String REMEDY = """
      Move @WorkflowService onto the class holding the @WorkflowTask methods of this workflow \
      aggregate. Where several classes are meant to share one declaration, put it on a common \
      superclass: @WorkflowService is @Inherited, so every subclass of an annotated class is a \
      workflow service of its own, reading the declaration off the superclass. Name the \
      'bpmnProcess' on that superclass where the subclasses are meant to serve ONE process - \
      without it each subclass names its process after itself.""";

  private WorkflowServiceBelongsOnAClass() {
  }

  /**
   * The message for the first shape of the defect. The platform which found it builds it.
   *
   * @param interfaceName The interface with the annotation
   * @param implementingClasses The classes which implement it, empty where the platform cannot
   *     name them
   * @return The message ending the start
   */
  public static String foundOnAnInterface(
      final String interfaceName,
      final List<String> implementingClasses) {

    return """
        @WorkflowService sits on the interface
          %s%s
        An interface cannot be the workflow service of a workflow aggregate. VanillaBP reads the \
        @WorkflowTask methods off the annotated type, and Java does not inherit a method annotation \
        from an interface, so a handler of the implementing class is invisible through it. Quarkus \
        would serve the methods declared in the interface, Spring Boot would find no handler at \
        all: one source file, two behaviours.
        %s"""
        .formatted(
            interfaceName,
            broughtInBy("implemented by", implementingClasses),
            REMEDY);

  }

  /**
   * The message for the second shape: a meta-annotation of the application's own.
   *
   * @param annotationName The annotation of the application which carries the annotation
   * @param annotatedClasses The classes which use it, empty where the platform cannot name
   *     them
   * @return The message ending the start
   */
  public static String foundOnAnAnnotation(
      final String annotationName,
      final List<String> annotatedClasses) {

    return """
        @WorkflowService sits on the annotation
          @%s%s
        An annotation of your own carrying @WorkflowService does not declare a workflow service. \
        Spring Boot resolves such a meta-annotation and reads the class using it, while Quarkus \
        reads its index, which reports the annotation type itself as the annotated one and never \
        sees your class: one platform would find your handler methods, the other an annotation.
        %s"""
        .formatted(
            annotationName,
            broughtInBy("used on", annotatedClasses),
            REMEDY);

  }

  /**
   * The types which brought the declaration into the application, one per line. It is empty
   * where the platform could not find them out. An interface which nobody implements is
   * refused as well, and then there is nothing to name.
   */
  private static String broughtInBy(
      final String relation,
      final List<String> typeNames) {

    if (typeNames.isEmpty()) {
      return "";
    }
    return "\n%s\n%s".formatted(
        relation,
        typeNames
            .stream()
            .map("  %s"::formatted)
            .collect(Collectors.joining("\n")));

  }

}
