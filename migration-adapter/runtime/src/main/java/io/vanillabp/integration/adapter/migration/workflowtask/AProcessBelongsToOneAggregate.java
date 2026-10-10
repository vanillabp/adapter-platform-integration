package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The refusal both platforms answer an application with where <code>&#64;WorkflowService</code>
 * classes of different workflow aggregates declare the same BPMN process of one workflow module.
 * Which aggregate the tasks of such a process load, and whether the process is a workflow of its
 * own or a step of another one, would otherwise depend on the order the classes are found in.
 * Why that ends the start, see decision 125 in the repository's {@code DECISIONS.md}.
 * <p>
 * The text lives in the core because three places say it: Spring Boot once it knows the
 * workflow module of every class, Quarkus while the application is built, and the registry of
 * task handlers, which every platform passes. The first two are there to say it early. The last
 * one is there for a platform which does not check.
 */
public final class AProcessBelongsToOneAggregate {

  /**
   * One class declaring one BPMN process.
   *
   * @param workflowModuleId The workflow module of the class
   * @param bpmnProcessId The BPMN process ID the class declares
   * @param workflowServiceClass The name of the class
   * @param workflowAggregateClass The name of the workflow aggregate of the class
   * @param asBpmnProcess Whether the class declares the process as its <code>bpmnProcess</code>,
   *     and not in its <code>secondaryBpmnProcesses</code>
   */
  public record Declaration(
                            String workflowModuleId,
                            String bpmnProcessId,
                            String workflowServiceClass,
                            String workflowAggregateClass,
                            boolean asBpmnProcess) {
  }

  private AProcessBelongsToOneAggregate() {
  }

  /**
   * Ends the start where at least one BPMN process is declared for more than one workflow
   * aggregate. Every such process is named in the one message.
   *
   * @param declarations Every BPMN process every workflow service class declares
   * @throws IllegalStateException If a process is declared for more than one aggregate
   */
  public static void refuseProcessesOfSeveralAggregates(
      final Collection<Declaration> declarations) {

    final var byProcess = new LinkedHashMap<String, List<Declaration>>();
    declarations
        .stream()
        .sorted(Comparator
            .comparing(Declaration::workflowModuleId)
            .thenComparing(Declaration::bpmnProcessId))
        .forEach(declaration -> byProcess
            .computeIfAbsent(
                "%s|%s".formatted(declaration.workflowModuleId(), declaration.bpmnProcessId()),
                key -> new java.util.LinkedList<>())
            .add(declaration));
    final var refusals = byProcess
        .values()
        .stream()
        .map(AProcessBelongsToOneAggregate::refusalOf)
        .flatMap(Optional::stream)
        .toList();
    if (!refusals.isEmpty()) {
      throw new IllegalStateException(String.join("\n\n", refusals));
    }

  }

  /**
   * The message for one BPMN process, where the classes declaring it name more than one
   * aggregate. The classes which start the process come first, and the classes are sorted by
   * name, so the text does not depend on the order they were found in.
   *
   * @param declarations The declarations of ONE BPMN process of one workflow module
   * @return The message, empty where all classes name the same aggregate
   */
  public static Optional<String> refusalOf(
      final Collection<Declaration> declarations) {

    final var sorted = declarations
        .stream()
        .distinct()
        // the classes which start the process first, then by name, so the text does not
        // depend on the order the classes were found in
        .sorted(Comparator
            .comparing(Declaration::asBpmnProcess, Comparator.reverseOrder())
            .thenComparing(Declaration::workflowServiceClass))
        .toList();
    final var aggregates = sorted
        .stream()
        .map(Declaration::workflowAggregateClass)
        .distinct()
        .toList();
    if (aggregates.size() < 2) {
      return Optional.empty();
    }
    final var first = sorted.getFirst();
    final var processId = first.bpmnProcessId();
    final var declaredBy = sorted
        .stream()
        .map(declaration -> "  %s (workflow aggregate %s) %s".formatted(
            declaration.workflowServiceClass(),
            declaration.workflowAggregateClass(),
            declaration.asBpmnProcess()
                ? "declares it as its 'bpmnProcess'"
                : "lists it in its 'secondaryBpmnProcesses'"))
        .collect(Collectors.joining("\n"));
    return Optional.of("""
        The BPMN process '%s' of workflow module '%s' is declared for %d workflow aggregates:
        %s
        A BPMN process belongs to exactly one workflow aggregate. Otherwise the order in which the \
        classes are found decides which aggregate the tasks of '%s' load, and whether '%s' is a \
        workflow of its own or a step of another workflow. So the start stops here.
        %s"""
        .formatted(
            processId,
            first.workflowModuleId(),
            aggregates.size(),
            declaredBy,
            processId,
            processId,
            adviceFor(processId, sorted)));

  }

  /**
   * What to do, for the way the classes declare the process: one of them as its
   * <code>bpmnProcess</code> and others as secondary, several as their
   * <code>bpmnProcess</code>, or all as secondary.
   */
  private static String adviceFor(
      final String processId,
      final List<Declaration> sorted) {

    final var asBpmnProcess = sorted
        .stream()
        .filter(Declaration::asBpmnProcess)
        .toList();
    final var aggregatesOfBpmnProcess = asBpmnProcess
        .stream()
        .map(Declaration::workflowAggregateClass)
        .distinct()
        .toList();

    if (aggregatesOfBpmnProcess.size() == 1) {
      // one aggregate starts the process, the others call it
      final var owner = aggregatesOfBpmnProcess.getFirst();
      final var starters = classesOf(asBpmnProcess, declaration -> true);
      final var callers = classesOf(
          sorted,
          declaration -> !declaration.workflowAggregateClass().equals(owner));
      return """
          Decide what '%1$s' is:
          - If '%1$s' is a workflow of its own, with the workflow aggregate %2$s, remove it from \
          'secondaryBpmnProcesses' of %3$s. %4$s
          - If '%1$s' is a step of the workflow of %3$s, only that class lists it in its \
          'secondaryBpmnProcesses'. Then %5$s must not declare it. Give that class a 'bpmnProcess' of \
          its own, or move its @WorkflowTask methods for '%1$s' into a class of the other workflow \
          aggregate."""
          .formatted(processId, owner, callers, callActivityAdvice(processId), starters);
    }

    if (aggregatesOfBpmnProcess.size() > 1) {
      // several aggregates start the process
      return """
          Decide which workflow aggregate '%1$s' belongs to. Only the classes of that aggregate \
          declare it. Each of the other classes declares a process of its own as its 'bpmnProcess'.
          - If the other workflow needs '%1$s' as a workflow of its own, it does not declare it. \
          %2$s
          - If '%1$s' is a step of the other workflow, only the class of that workflow lists it, in \
          its 'secondaryBpmnProcesses'."""
          .formatted(processId, callActivityAdvice(processId));
    }

    // every class lists the process as secondary: two workflows call it
    return """
        '%1$s' can be a step of only one workflow. Keep it in 'secondaryBpmnProcesses' of the class \
        whose workflow it is a step of, and remove it from the others.
        - If the other workflow needs the same steps, give it a copy of the process with a BPMN \
        process ID of its own.
        - If '%1$s' is meant to be a workflow of its own, declare it as the 'bpmnProcess' of a \
        class with a workflow aggregate of its own, and remove it from all \
        'secondaryBpmnProcesses'. %2$s"""
        .formatted(processId, callActivityAdvice(processId));

  }

  /**
   * The same in every case: what it means to call a workflow of its own by a call activity,
   * and what is usually better. The data the called workflow gets are named in words every
   * BPMS understands, since not every BPMS has process variables.
   */
  private static String callActivityAdvice(
      final String processId) {

    return """
        A call activity may still call '%1$s'. But then the call is a matter of your BPMS. The \
        data the called workflow gets are set up by the mappings of the call activity in your \
        BPMS, not by VanillaBP. Two separate workflows are usually better started and \
        synchronised by messages. Then the contract between them lives in your business code and \
        not in the BPMN."""
        .formatted(processId);

  }

  private static String classesOf(
      final List<Declaration> declarations,
      final Predicate<Declaration> filter) {

    return declarations
        .stream()
        .filter(filter)
        .map(Declaration::workflowServiceClass)
        .distinct()
        .collect(Collectors.joining(" and "));

  }

}
