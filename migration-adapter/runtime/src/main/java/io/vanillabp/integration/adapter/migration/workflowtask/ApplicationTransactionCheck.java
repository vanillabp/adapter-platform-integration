package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.vanillabp.spi.service.TaskException;

/**
 * Finds transaction annotations of the application covering a
 * <code>&#64;WorkflowTask</code> method. Such an annotation joins the transaction
 * VanillaBP runs the method in and marks it rollback-only as soon as a
 * {@link TaskException} passes it, which discards everything the handler wrote onto
 * the workflow aggregate although the workflow takes the BPMN error path. A
 * rollback-only mark cannot be cleared, so the only thing VanillaBP can do is refuse
 * to boot and say why.
 * <p>
 * The core must not depend on Spring or Quarkus, so the annotations are matched by
 * their type NAME and their members are read reflectively. Two annotations are
 * accepted: one whose propagation does not join an existing transaction, and one
 * whose rollback rules exclude {@link TaskException} (the pattern a VanillaBP 1
 * application carries, which keeps working).
 */
final class ApplicationTransactionCheck {

  private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

  private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

  private static final String EJB_TRANSACTION_ATTRIBUTE = "jakarta.ejb.TransactionAttribute";

  /**
   * Honored by neither Spring Framework 7 nor Quarkus 3: the annotation fell out
   * with the move to the Jakarta namespace. It does not break VanillaBP's contract,
   * it does nothing at all - which is a trap of its own for an application coming
   * from Spring Boot 2.
   */
  private static final String OBSOLETE_JAVAX_TRANSACTIONAL = "javax.transaction.Transactional";

  private static final Set<String> TRANSACTION_ANNOTATIONS = Set.of(
      SPRING_TRANSACTIONAL,
      JAKARTA_TRANSACTIONAL,
      EJB_TRANSACTION_ATTRIBUTE);

  /**
   * Propagations joining a transaction already active, i.e. VanillaBP's. The others
   * (<code>REQUIRES_NEW</code>, <code>NOT_SUPPORTED</code>, <code>NEVER</code> and
   * Spring's savepoint-based <code>NESTED</code>) keep the application's rollback
   * inside the application's own transaction, which is what it asked for.
   */
  private static final Set<String> JOINING_PROPAGATIONS = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

  private static final int NO_MATCH = Integer.MAX_VALUE;

  /**
   * What the check found for one <code>&#64;WorkflowTask</code> method: a defect
   * failing the startup, a hint about an annotation without effect, or neither.
   *
   * @param defect The description of the offending annotation, or <code>null</code>
   * @param obsoleteAnnotation Where an ineffective
   *          <code>javax.transaction.Transactional</code> sits, or <code>null</code>
   */
  record Findings(
                  String defect,
                  String obsoleteAnnotation) {
  }

  private ApplicationTransactionCheck() {
  }

  static Findings inspect(
      final Class<?> workflowServiceClass,
      final Method method) {

    final var searchOrder = searchOrder(workflowServiceClass, method);
    return new Findings(
        findDefect(searchOrder, workflowServiceClass, method), findObsoleteAnnotation(searchOrder));

  }

  /**
   * Builds the message of the exception failing the startup. The remedies are stated
   * once for all offending methods.
   *
   * @param defects The defects found, in the order the methods were scanned
   * @param workflowServiceClass The scanned <code>&#64;WorkflowService</code> class
   * @return The message
   */
  static String buildFailureMessage(
      final List<String> defects,
      final Class<?> workflowServiceClass) {

    final var message = new StringBuilder(
        "@WorkflowTask methods of '%s' are covered by a transaction annotation of the application:"
            .formatted(workflowServiceClass.getName()));
    defects.forEach(defect -> message
        .append("\n  - ")
        .append(defect));
    message.append(
        """


            Such an annotation joins the transaction VanillaBP runs the method in and marks it \
            rollback-only as soon as a TaskException passes it: the workflow takes the BPMN error \
            path, but NONE of the changes made to the workflow aggregate are committed. To solve \
            this either
             - remove the annotation from the workflow task method (methods calling ProcessService \
            still need their own transaction - annotate those instead of the whole class), or
             - exclude VanillaBP's TaskException from the rollback rules:
               @Transactional(noRollbackFor = TaskException.class) for the Spring annotation,
               @Transactional(dontRollbackOn = TaskException.class) for the jakarta.transaction one.""");
    return message.toString();

  }

  private static String findDefect(
      final List<AnnotatedElement> searchOrder,
      final Class<?> workflowServiceClass,
      final Method method) {

    // most specific declaration wins, like Spring's
    // AnnotationTransactionAttributeSource resolves it
    for (final var element : searchOrder) {
      final var found = findTransactionAnnotation(element, TRANSACTION_ANNOTATIONS);
      if (found.isEmpty()) {
        continue;
      }
      final var annotation = found.get();
      if (!joinsTransaction(annotation.annotation()) || excludesTaskException(annotation.annotation())) {
        // an accepted annotation ends the search: a less specific one is
        // overridden by it, exactly like the platforms resolve it
        return null;
      }
      return "the @WorkflowTask method '%s#%s' is covered by '@%s' %s".formatted(
          workflowServiceClass.getName(),
          method.getName(),
          annotation
              .annotation()
              .annotationType()
              .getName(),
          annotation.describeOrigin());
    }
    return null;

  }

  private static String findObsoleteAnnotation(
      final List<AnnotatedElement> searchOrder) {

    return searchOrder
        .stream()
        .map(element -> findTransactionAnnotation(element, Set.of(OBSOLETE_JAVAX_TRANSACTIONAL)))
        .flatMap(Optional::stream)
        .findFirst()
        .map(FoundAnnotation::describeOrigin)
        .orElse(null);

  }

  /**
   * The elements a transaction annotation may sit on, most specific first: the
   * method, the same method as declared by a superclass or interface, then the
   * class, its superclasses and its interfaces.
   */
  private static List<AnnotatedElement> searchOrder(
      final Class<?> workflowServiceClass,
      final Method method) {

    final var elements = new LinkedList<AnnotatedElement>();
    elements.add(method);
    final var types = hierarchyOf(workflowServiceClass);
    types
        .stream()
        .filter(type -> !type.equals(method.getDeclaringClass()))
        .map(type -> declaredMethodOf(type, method))
        .flatMap(Optional::stream)
        .forEach(elements::add);
    elements.addAll(types);
    return elements;

  }

  private static Optional<Method> declaredMethodOf(
      final Class<?> type,
      final Method method) {

    try {
      return Optional.of(type.getDeclaredMethod(method.getName(), method.getParameterTypes()));
    } catch (final NoSuchMethodException e) {
      return Optional.empty();
    }

  }

  /**
   * The class itself, its superclasses up to (excluding) {@link Object} and all
   * interfaces it implements, transitively.
   */
  private static Set<Class<?>> hierarchyOf(
      final Class<?> type) {

    final var types = new LinkedHashSet<Class<?>>();
    for (var current = type; (current != null) && !current.equals(Object.class); current = current.getSuperclass()) {
      types.add(current);
      collectInterfaces(current, types);
    }
    return types;

  }

  private static void collectInterfaces(
      final Class<?> type,
      final Set<Class<?>> collected) {

    for (final var implemented : type.getInterfaces()) {
      if (collected.add(implemented)) {
        collectInterfaces(implemented, collected);
      }
    }

  }

  /**
   * A transaction annotation found on an element, either declared there or carried
   * by an annotation declared there (a custom annotation meta-annotated with
   * <code>&#64;Transactional</code> is a common house style, and a purely name-based
   * scan would miss it).
   */
  private record FoundAnnotation(
                                 Annotation annotation,
                                 AnnotatedElement element,
                                 Annotation metaAnnotated) {

    String describeOrigin() {

      final var via = metaAnnotated == null
          ? ""
          : " via '@%s'".formatted(metaAnnotated
              .annotationType()
              .getName());
      if (element instanceof Method method) {
        return "declared on the method '%s#%s'%s".formatted(
            method
                .getDeclaringClass()
                .getName(),
            method.getName(),
            via);
      }
      final var type = (Class<?>) element;
      return "declared on the %s '%s'%s".formatted(
          type.isInterface()
              ? "interface"
              : "class",
          type.getName(),
          via);

    }

  }

  private static Optional<FoundAnnotation> findTransactionAnnotation(
      final AnnotatedElement element,
      final Set<String> annotationTypeNames) {

    for (final var annotation : element.getAnnotations()) {
      if (annotationTypeNames.contains(annotation
          .annotationType()
          .getName())) {
        return Optional.of(new FoundAnnotation(annotation, element, null));
      }
    }
    // meta-annotations, one level deep
    for (final var annotation : element.getAnnotations()) {
      for (final var metaAnnotation : annotation
          .annotationType()
          .getAnnotations()) {
        if (annotationTypeNames.contains(metaAnnotation
            .annotationType()
            .getName())) {
          return Optional.of(new FoundAnnotation(metaAnnotation, element, annotation));
        }
      }
    }
    return Optional.empty();

  }

  private static boolean joinsTransaction(
      final Annotation annotation) {

    final var memberName = SPRING_TRANSACTIONAL.equals(annotation
        .annotationType()
        .getName())
            ? "propagation"
            : "value";
    return member(annotation, memberName)
        .filter(Enum.class::isInstance)
        .map(Enum.class::cast)
        .map(Enum::name)
        // all three annotations default to REQUIRED, which joins
        .map(JOINING_PROPAGATIONS::contains)
        .orElse(Boolean.TRUE);

  }

  private static boolean excludesTaskException(
      final Annotation annotation) {

    final var annotationType = annotation
        .annotationType()
        .getName();
    // both platforms apply the MOST SPECIFIC rule, so a no-rollback rule naming
    // TaskException wins over a broader rollback rule but loses against an equally or
    // more specific one. Spring resolves ties in favor of rolling back, and a pair of
    // equally specific contradicting rules is a defect either way.
    if (SPRING_TRANSACTIONAL.equals(annotationType)) {
      final var noRollback = Math.min(
          bestMatch(classMember(annotation, "noRollbackFor")),
          bestNameMatch(annotation, "noRollbackForClassName"));
      final var rollback = Math.min(
          bestMatch(classMember(annotation, "rollbackFor")),
          bestNameMatch(annotation, "rollbackForClassName"));
      return (noRollback != NO_MATCH) && (noRollback < rollback);
    }
    if (JAKARTA_TRANSACTIONAL.equals(annotationType)) {
      final var dontRollback = bestMatch(classMember(annotation, "dontRollbackOn"));
      return (dontRollback != NO_MATCH) && (dontRollback < bestMatch(classMember(annotation, "rollbackOn")));
    }
    // jakarta.ejb.TransactionAttribute has no rollback rules
    return false;

  }

  /**
   * Spring's rule members taking class NAMES match a pattern against the name of the
   * exception and of its supertypes, so the distance is determined the same way as for
   * the members taking classes.
   */
  private static int bestNameMatch(
      final Annotation annotation,
      final String memberName) {

    final var patterns = member(annotation, memberName)
        .filter(String[].class::isInstance)
        .map(String[].class::cast)
        .orElse(new String[0]);
    var best = NO_MATCH;
    for (final var pattern : patterns) {
      var distance = 0;
      for (var type = (Class<?>) TaskException.class; type != null; type = type.getSuperclass(), distance++) {
        if (type
            .getName()
            .contains(pattern)) {
          best = Math.min(best, distance);
          break;
        }
      }
    }
    return best;

  }

  private static Class<?>[] classMember(
      final Annotation annotation,
      final String memberName) {

    return member(annotation, memberName)
        .filter(Class[].class::isInstance)
        .map(Class[].class::cast)
        .orElse(new Class<?>[0]);

  }

  /**
   * The inheritance distance of the most specific of the given exception types
   * covering a {@link TaskException}, or {@link #NO_MATCH} if none does. A
   * superclass of {@link TaskException} covers it, like the platforms resolve it.
   */
  private static int bestMatch(
      final Class<?>[] exceptionTypes) {

    var best = NO_MATCH;
    for (final var exceptionType : exceptionTypes) {
      var distance = 0;
      for (var type = (Class<?>) TaskException.class; type != null; type = type.getSuperclass(), distance++) {
        if (type.equals(exceptionType)) {
          best = Math.min(best, distance);
          break;
        }
      }
    }
    return best;

  }

  private static Optional<Object> member(
      final Annotation annotation,
      final String memberName) {

    try {
      return Optional.ofNullable(annotation
          .annotationType()
          .getMethod(memberName)
          .invoke(annotation));
    } catch (final ReflectiveOperationException e) {
      // an annotation of that name without that member: not the annotation we
      // expect, so nothing can be concluded from it
      return Optional.empty();
    }

  }

}
