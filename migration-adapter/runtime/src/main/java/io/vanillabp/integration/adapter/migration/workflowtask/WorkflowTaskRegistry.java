package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * The core-owned registry of <code>&#64;WorkflowTask</code> handlers per (workflow
 * module, BPMN process ID) and the implementation of the adapter-facing
 * {@link WorkflowTaskInvoker}. The platform integration registers every workflow
 * service class under all BPMN process IDs it declares
 * ({@code @WorkflowService.bpmnProcess} and {@code secondaryBpmnProcesses});
 * adapters validate the wiring during <code>wireBpmn</code> and dispatch task
 * invocations at runtime.
 */
public class WorkflowTaskRegistry implements WorkflowTaskInvoker {

  private static final Logger log = LoggerFactory.getLogger(WorkflowTaskRegistry.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private static class RegistryEntry {

    private final List<WorkflowTaskHandler> handlers = new LinkedList<>();

    private final List<Class<?>> workflowServiceClasses = new LinkedList<>();

    private MigrationProcessService<?> processService;

  }

  private final TransactionRunner transactionRunner;

  private final Map<RegistryKey, RegistryEntry> entries = new ConcurrentHashMap<>();

  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner) {

    this.transactionRunner = transactionRunner;

  }

  /**
   * Registers all <code>&#64;WorkflowTask</code> methods of the given workflow
   * service class for the given BPMN process. Called by the platform integration at
   * startup, once per (workflow service class, declared BPMN process ID).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowServiceBean Supplies the bean instance of that class (resolved
   *          lazily to avoid materializing beans at registration time)
   * @param beanResolver Resolves beans by class (used for
   *          <code>&#64;MultiInstanceElement(resolverBean = ...)</code>)
   * @param processService The process service of the BPMN process (aggregate
   *          persistence and ID conversion)
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final MigrationProcessService<?> processService) {

    final var entry = entries.computeIfAbsent(
        new RegistryKey(workflowModuleId, bpmnProcessId),
        key -> new RegistryEntry());
    synchronized (entry) {
      // V1 semantics: if more than one @WorkflowService class declares the same
      // BPMN process for DIFFERENT aggregates, the one previously built wins -
      // later classes are skipped with a warning (same-aggregate classes merge)
      if ((entry.processService != null) && !entry.processService.getWorkflowAggregateClass()
          .equals(processService.getWorkflowAggregateClass())) {
        log.warn(
            """
                The @WorkflowService class '{}' (aggregate '{}') declares BPMN process '{}' of \
                workflow module '{}' which is already served by '{}' (aggregate '{}') - the class \
                found first wins, '{}' is ignored for this BPMN process.""",
            workflowServiceClass.getName(),
            processService.getWorkflowAggregateClass().getName(),
            bpmnProcessId,
            workflowModuleId,
            entry.workflowServiceClasses.getFirst().getName(),
            entry.processService.getWorkflowAggregateClass().getName(),
            workflowServiceClass.getName());
        return;
      }
      final var handlers = WorkflowTaskScanner.scan(
          workflowServiceClass,
          processService.getWorkflowAggregateClass(),
          workflowServiceBean,
          beanResolver);
      handlers.forEach(handler -> failOnDuplicateWiring(workflowModuleId, bpmnProcessId, entry, handler));
      entry.handlers.addAll(handlers);
      entry.workflowServiceClasses.add(workflowServiceClass);
      entry.processService = processService;
    }

  }

  private static void failOnDuplicateWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RegistryEntry entry,
      final WorkflowTaskHandler handler) {

    final var duplicate = entry.handlers
        .stream()
        .filter(existing -> sameWiring(existing.getTaskDefinition(),
            handler.getTaskDefinition()) || sameWiring(existing.getActivityId(), handler.getActivityId()))
        // both matching every version = genuinely ambiguous; disjoint version
        // ranges are a legitimate way to serve several process versions
        .filter(existing -> existing.matchesVersion(null) && handler.matchesVersion(null))
        .findFirst();
    if (duplicate.isPresent()) {
      throw new IllegalStateException(
          """
              The @WorkflowTask methods '%s' and '%s' are both wired to %s of BPMN process '%s' \
              of workflow module '%s'! Remove one of them or distinguish them by \
              @WorkflowTask(version = ...)."""
              .formatted(
                  duplicate.get().describe(),
                  handler.describe(),
                  handler.describeWiring(),
                  bpmnProcessId,
                  workflowModuleId));
    }

  }

  private static boolean sameWiring(
      final String first,
      final String second) {

    return (first != null) && first.equals(second);

  }

  @Override
  public void validateTaskWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmnTaskSpec> tasks) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    final var handlers = entry == null
        ? List.<WorkflowTaskHandler>of()
        : List.copyOf(entry.handlers);

    final var unmatchedTasks = tasks
        .stream()
        .filter(task -> handlers.stream().noneMatch(handler -> matches(handler, task)))
        .toList();
    final var unmatchedHandlers = handlers
        .stream()
        .filter(handler -> tasks.stream().noneMatch(task -> matches(handler, task)))
        .toList();
    if (unmatchedTasks.isEmpty() && unmatchedHandlers.isEmpty()) {
      return;
    }

    final var message = new StringBuilder(
        "Task wiring of BPMN process '%s' of workflow module '%s' is incomplete!"
            .formatted(bpmnProcessId, workflowModuleId));
    if (!unmatchedTasks.isEmpty()) {
      final var serviceClasses = (entry == null) || entry.workflowServiceClasses.isEmpty()
          ? "a @WorkflowService class responsible for this BPMN process"
          : entry.workflowServiceClasses
              .stream()
              .map(Class::getName)
              .collect(Collectors.joining("', '", "'", "'"));
      message.append("\nBPMN tasks having no matching @WorkflowTask method:");
      unmatchedTasks.forEach(task -> message.append(
          """

                - task '%s' (task definition '%s'): add a method annotated with @WorkflowTask named \
              '%s' to %s, or annotate an existing method with @WorkflowTask(taskDefinition = "%s") \
              or @WorkflowTask(id = "%s")."""
              .formatted(
                  task.activityId(),
                  task.taskDefinition(),
                  task.taskDefinition() != null
                      ? task.taskDefinition()
                      : task.activityId(),
                  serviceClasses,
                  task.taskDefinition(),
                  task.activityId())));
    }
    if (!unmatchedHandlers.isEmpty()) {
      message.append("\n@WorkflowTask methods matching no task of the BPMN process:");
      unmatchedHandlers.forEach(handler -> message.append(
          """

                - method '%s' (wired by %s): fix the annotation's taskDefinition/id or remove the \
              annotation."""
              .formatted(handler.describe(), handler.describeWiring())));
    }
    message.append("\nTasks of the BPMN process: ");
    message.append(tasks.isEmpty()
        ? "none"
        : tasks
            .stream()
            .map(task -> "'%s' (task definition '%s')".formatted(task.activityId(), task.taskDefinition()))
            .collect(Collectors.joining(", ")));
    message.append(". @WorkflowTask methods found: ");
    message.append(handlers.isEmpty()
        ? "none"
        : handlers
            .stream()
            .map(handler -> "'%s' (%s)".formatted(handler.describe(), handler.describeWiring()))
            .collect(Collectors.joining(", ")));
    message.append('.');
    throw new IllegalStateException(message.toString());

  }

  private static boolean matches(
      final WorkflowTaskHandler handler,
      final BpmnTaskSpec task) {

    return sameWiring(handler.getTaskDefinition(), task.taskDefinition()) || sameWiring(handler.getActivityId(),
        task.activityId());

  }

  @Override
  public WorkflowTaskOutcome invokeWorkflowTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext context) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      throw new IllegalStateException(
          """
              No @WorkflowService class is registered for BPMN process '%s' of workflow module \
              '%s'! Known processes: %s."""
              .formatted(
                  bpmnProcessId,
                  workflowModuleId,
                  entries
                      .keySet()
                      .stream()
                      .map(key -> "'%s' (module '%s')".formatted(key.bpmnProcessId(), key.workflowModuleId()))
                      .collect(Collectors.joining(", "))));
    }
    final var handler = entry.handlers
        .stream()
        .filter(candidate -> sameWiring(candidate.getTaskDefinition(),
            context.getTaskDefinition()) || sameWiring(candidate.getActivityId(), context.getTaskDefinition()))
        .filter(candidate -> candidate.matchesVersion(context.getProcessVersion()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                No @WorkflowTask method of BPMN process '%s' of workflow module '%s' matches task \
                definition '%s' (process version '%s')! Registered methods: %s."""
                .formatted(
                    bpmnProcessId,
                    workflowModuleId,
                    context.getTaskDefinition(),
                    context.getProcessVersion(),
                    entry.handlers
                        .stream()
                        .map(candidate -> "'%s' (%s)".formatted(candidate.describe(), candidate.describeWiring()))
                        .collect(Collectors.joining(", ")))));
    return entry.processService.executeWorkflowTask(handler, context, transactionRunner);

  }

}
