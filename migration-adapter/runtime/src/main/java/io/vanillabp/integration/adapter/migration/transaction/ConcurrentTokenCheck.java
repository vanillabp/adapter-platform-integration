package io.vanillabp.integration.adapter.migration.transaction;

import java.lang.reflect.AnnotatedElement;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The startup hint about two writers on one workflow aggregate: a BPMN
 * process which can hold more than one token at a time has branches writing the same
 * workflow aggregate, and without a version attribute the branch committing second
 * writes back what it read when it started - silently, since an ORM saves the whole
 * record.
 * <p>
 * Reading a model is the adapter's job (only it can parse its BPMN dialect), deciding
 * what the finding means is the core's: the adapter reports the elements which can put
 * a second token into a running workflow, and the core knows the aggregate class.
 * <p>
 * A version attribute silences the hint - the collision then raises an exception
 * instead of overwriting, which is what {@link AggregateWrite} reports. So does an
 * adapter which cannot read its models (the Process-Engine-API): it reports nothing,
 * and nothing is guessed from the absence.
 * <p>
 * Why a model which can produce a second token is only warned about, and only where the aggregate
 * has no version attribute, is decision 14 in the repository's DECISIONS.md.
 */
public class ConcurrentTokenCheck {

  private static final Logger log = LoggerFactory.getLogger(ConcurrentTokenCheck.class);

  /**
   * The annotations marking the attribute a persistence layer increments per write,
   * matched by NAME: the core is plain Java and must not gain a dependency on JPA or
   * Spring Data. Every persistence layer VanillaBP supports calls it
   * <code>Version</code> (<code>jakarta.persistence</code>,
   * <code>org.springframework.data.annotation</code>), so the SIMPLE name decides -
   * an unknown persistence layer following the same convention is recognized as well,
   * and the outcome of a false positive is a hint not given.
   */
  private static final String VERSION_ANNOTATION = "Version";


  /**
   * The (workflow module, BPMN process) pairs already reported - the hint is a design
   * message, not a linter running per deployed file.
   */
  private final Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * Reports the elements an adapter found in a BPMN process which can put a second
   * token into a running workflow, and warns if that process' workflow aggregate has
   * no version attribute.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param elementIds The BPMN element IDs producing the second token
   */
  public void reportConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final Collection<String> elementIds) {

    if ((elementIds == null) || elementIds.isEmpty() || (workflowAggregateClass == null)) {
      return;
    }
    if (hasVersionAttribute(workflowAggregateClass)) {
      return;
    }
    if (!reported.add(workflowModuleId
        + "#"
        + bpmnProcessId)) {
      return;
    }

    log
        .warn(
            """
                The BPMN process '{}' of workflow module '{}' can hold more than one token at a time \
                ({}), but its workflow aggregate '{}' has no version attribute (@Version): two \
                branches load the aggregate, change different things and save it - and since the \
                persistence layer writes the whole record, whatever the branch committing first \
                changed is lost without any error. Ways out: one entity per phase of the workflow, \
                @DynamicUpdate where the branches write different attributes, a version attribute \
                plus a retry in the transaction your application opens, or an additive relation \
                instead of a mutated attribute. The wiki page 'Workflow aggregates' compares them. \
                A version attribute turns the collision into an exception VanillaBP reports and the \
                BPMS retries, which is why this message is about its absence.""",
            bpmnProcessId,
            workflowModuleId,
            elementIds
                .stream()
                .distinct()
                .sorted()
                .collect(Collectors.joining("', '", "e.g. '", "'")),
            workflowAggregateClass.getName());

  }

  /**
   * Whether the given class or one of its super classes declares an attribute the
   * persistence layer uses for optimistic locking - answered by the NAMES of the
   * annotations (JPA, Spring Data), so the core stays free of both.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return Whether a version attribute was found
   */
  public static boolean hasVersionAttribute(
      final Class<?> workflowAggregateClass) {

    var type = workflowAggregateClass;
    while ((type != null) && (type != Object.class)) {
      final var annotated = java.util.stream.Stream
          .concat(
              java.util.Arrays.stream(type.getDeclaredFields()),
              java.util.Arrays.stream(type.getDeclaredMethods()))
          .map(AnnotatedElement.class::cast)
          .toList();
      if (annotated
          .stream()
          .anyMatch(ConcurrentTokenCheck::isVersionAnnotated)) {
        return true;
      }
      type = type.getSuperclass();
    }
    return false;

  }

  private static boolean isVersionAnnotated(
      final AnnotatedElement element) {

    return List
        .of(element.getAnnotations())
        .stream()
        .map(annotation -> annotation
            .annotationType()
            .getSimpleName())
        .anyMatch(VERSION_ANNOTATION::equals);

  }

}
