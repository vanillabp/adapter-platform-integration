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
 * {@link TaskException} passes it, which discards everything the handler wrote onto the
 * workflow aggregate although the workflow takes the BPMN error path. A rollback-only
 * mark cannot be cleared, so the only thing VanillaBP can do is refuse to boot and say
 * why.
 * <p>
 * Which annotations exist, and whether the running platform honors them at all, is
 * supplied by the platform integration as {@link TransactionAnnotationSpec}s. This class
 * owns the judgement and nothing else: the search order, meta-annotations, the
 * propagation and the specificity of the rollback rules. Annotations are matched by type
 * NAME and their members are read reflectively, so the core depends on neither platform.
 * <p>
 * Two annotations are accepted: one whose propagation does not join an existing
 * transaction, and one whose rollback rules exclude {@link TaskException} (the pattern a
 * VanillaBP 1 application carries, which keeps working).
 */
final class ApplicationTransactionCheck {

  private static final int NO_MATCH = Integer.MAX_VALUE;

  /**
   * What the check found for one <code>&#64;WorkflowTask</code> method: a defect failing
   * the startup, a hint about an annotation without effect on this platform, or neither.
   *
   * @param defect The description of the offending annotation, or <code>null</code>
   * @param remedy How the offending annotation may be written instead, or
   *          <code>null</code> if it has no rollback rules
   * @param notHonored The description of an annotation this platform does not honor, or
   *          <code>null</code>
   */
  record Findings(
                  String defect,
                  String remedy,
                  String notHonored) {
  }

  private ApplicationTransactionCheck() {
  }

  static Findings inspect(
      final Class<?> workflowServiceClass,
      final Method method,
      final List<TransactionAnnotationSpec> specs) {

    final var searchOrder = searchOrder(workflowServiceClass, method);
    // most specific declaration wins, like Spring's
    // AnnotationTransactionAttributeSource and CDI's interceptor bindings resolve it
    for (final var element : searchOrder) {
      final var found = findTransactionAnnotation(element, specs);
      if (found.isEmpty()) {
        continue;
      }
      final var annotation = found.get();
      if (!annotation
          .spec()
          .honored()) {
        return new Findings(
            null, null, "'@%s' %s: %s".formatted(
                annotation
                    .spec()
                    .annotationType(),
                annotation.describeOrigin(),
                annotation
                    .spec()
                    .notHonoredHint()));
      }
      if (!joinsTransaction(annotation) || excludesTaskException(annotation)) {
        // an accepted annotation ends the search: a less specific one is overridden
        // by it, exactly like the platforms resolve it
        return new Findings(null, null, null);
      }
      return new Findings(
          "the @WorkflowTask method '%s#%s' is covered by '@%s' %s".formatted(
              workflowServiceClass.getName(),
              method.getName(),
              annotation
                  .spec()
                  .annotationType(),
              annotation.describeOrigin()), annotation
                  .spec()
                  .remedy(), null);
    }
    return new Findings(null, null, null);

  }

  /**
   * Builds the message of the exception failing the startup. The remedies are stated
   * once for all offending methods.
   *
   * @param defects The defects found, in the order the methods were scanned
   * @param remedies The remedies of the offending annotations, without duplicates
   * @param workflowServiceClass The scanned <code>&#64;WorkflowService</code> class
   * @return The message
   */
  static String buildFailureMessage(
      final List<String> defects,
      final Set<String> remedies,
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
            this remove the annotation from the workflow task method - methods calling \
            ProcessService still need their own transaction, so annotate those instead of the \
            whole class.""");
    if (remedies.isEmpty()) {
      message.append(
          """

              A propagation not joining an existing transaction (e.g. REQUIRES_NEW) is accepted as \
              well; this annotation has no rollback rules which could exclude the TaskException.""");
    } else {
      message.append(
          """

              A propagation not joining an existing transaction (e.g. REQUIRES_NEW) is accepted as \
              well, and so is excluding VanillaBP's TaskException from the rollback rules:""");
      remedies.forEach(remedy -> message
          .append("\n  ")
          .append(remedy));
    }
    return message.toString();

  }

  /**
   * The elements a transaction annotation may sit on, most specific first: the method,
   * the same method as declared by a superclass or interface, then the class, its
   * superclasses and its interfaces.
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
   * A transaction annotation found on an element, either declared there or carried by an
   * annotation declared there (a custom annotation meta-annotated with
   * <code>&#64;Transactional</code> is a common house style, and a purely name-based
   * scan would miss it).
   */
  private record FoundAnnotation(
                                 Annotation annotation,
                                 TransactionAnnotationSpec spec,
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
      final List<TransactionAnnotationSpec> specs) {

    for (final var annotation : element.getAnnotations()) {
      final var spec = specOf(annotation, specs);
      if (spec.isPresent()) {
        return Optional.of(new FoundAnnotation(annotation, spec.get(), element, null));
      }
    }
    // meta-annotations, one level deep
    for (final var annotation : element.getAnnotations()) {
      for (final var metaAnnotation : annotation
          .annotationType()
          .getAnnotations()) {
        final var spec = specOf(metaAnnotation, specs);
        if (spec.isPresent()) {
          return Optional.of(new FoundAnnotation(metaAnnotation, spec.get(), element, annotation));
        }
      }
    }
    return Optional.empty();

  }

  private static Optional<TransactionAnnotationSpec> specOf(
      final Annotation annotation,
      final List<TransactionAnnotationSpec> specs) {

    return specs
        .stream()
        .filter(spec -> spec
            .annotationType()
            .equals(annotation
                .annotationType()
                .getName()))
        .findFirst();

  }

  private static boolean joinsTransaction(
      final FoundAnnotation found) {

    return member(found.annotation(), found
        .spec()
        .propagationMember())
        .filter(Enum.class::isInstance)
        .map(Enum.class::cast)
        .map(Enum::name)
        // all known annotations default to REQUIRED, which joins
        .map(found
            .spec()
            .joiningPropagations()::contains)
        .orElse(Boolean.TRUE);

  }

  /**
   * Both platforms apply the MOST SPECIFIC rollback rule, so a no-rollback rule naming
   * {@link TaskException} wins over a broader rollback rule but loses against an equally
   * or more specific one. Spring resolves ties in favor of rolling back, and a pair of
   * equally specific contradicting rules is a defect either way.
   */
  private static boolean excludesTaskException(
      final FoundAnnotation found) {

    final var noRollback = Math.min(
        bestMatch(found, found
            .spec()
            .noRollbackForMembers()),
        bestNameMatch(found, found
            .spec()
            .noRollbackForClassNameMembers()));
    final var rollback = Math.min(
        bestMatch(found, found
            .spec()
            .rollbackForMembers()),
        bestNameMatch(found, found
            .spec()
            .rollbackForClassNameMembers()));
    return (noRollback != NO_MATCH) && (noRollback < rollback);

  }

  /**
   * The inheritance distance of the most specific exception type covering a
   * {@link TaskException} among the given class-valued members, or {@link #NO_MATCH} if
   * none does. A superclass of {@link TaskException} covers it, like the platforms
   * resolve it.
   */
  private static int bestMatch(
      final FoundAnnotation found,
      final List<String> memberNames) {

    var best = NO_MATCH;
    for (final var memberName : memberNames) {
      final var exceptionTypes = member(found.annotation(), memberName)
          .filter(Class[].class::isInstance)
          .map(Class[].class::cast)
          .orElse(new Class<?>[0]);
      for (final var exceptionType : exceptionTypes) {
        var distance = 0;
        for (var type = (Class<?>) TaskException.class; type != null; type = type.getSuperclass(), distance++) {
          if (type.equals(exceptionType)) {
            best = Math.min(best, distance);
            break;
          }
        }
      }
    }
    return best;

  }

  /**
   * Members taking class NAMES match a pattern against the name of the exception and of
   * its supertypes, so the distance is determined the same way as for the members taking
   * classes.
   */
  private static int bestNameMatch(
      final FoundAnnotation found,
      final List<String> memberNames) {

    var best = NO_MATCH;
    for (final var memberName : memberNames) {
      final var patterns = member(found.annotation(), memberName)
          .filter(String[].class::isInstance)
          .map(String[].class::cast)
          .orElse(new String[0]);
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
      // an annotation of that name without that member: not the annotation the spec
      // describes, so nothing can be concluded from it
      return Optional.empty();
    }

  }

}
