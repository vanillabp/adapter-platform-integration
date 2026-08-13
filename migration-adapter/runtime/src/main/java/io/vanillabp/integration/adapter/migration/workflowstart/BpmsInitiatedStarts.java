package io.vanillabp.integration.adapter.migration.workflowstart;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;

/**
 * The <code>&#64;WorkflowStartedByBpms</code> methods of the application per
 * (workflow module, BPMN process), and the start events the adapters reported for
 * those processes. Backs the adapter-facing
 * {@link io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker},
 * which the workflow-task registry implements by delegating here.
 */
public class BpmsInitiatedStarts {

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private static class RegistryEntry {

    private final List<BpmsInitiatedStartHandler> handlers = new LinkedList<>();

    /**
     * The start events reported by the adapters deploying this process. Several
     * adapters may report the same process (the migration case) - the union is
     * what the application may serve.
     */
    private final List<BpmsInitiatedStartSpec> startEvents = new LinkedList<>();

  }

  private final Map<RegistryKey, RegistryEntry> entries = new ConcurrentHashMap<>();

  /**
   * What the BPMS know about the deployed versions of the BPMN processes - owned by the
   * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}
   * and shared, since all three annotations carry a <code>version</code> attribute.
   */
  private final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions;

  public BpmsInitiatedStarts(
      final io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions processVersions) {

    this.processVersions = processVersions;

  }

  /**
   * Scans a workflow service class and registers what it found. Called by the
   * platform integration at startup, once per (workflow service class, declared
   * BPMN process ID).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass The workflow-aggregate class
   * @param workflowServiceBean Supplies the bean instance of that class
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean) {

    final var handlers = BpmsInitiatedStartScanner
        .scan(workflowServiceClass, workflowAggregateClass, workflowServiceBean);
    if (handlers.isEmpty()) {
      return;
    }
    final var entry = entries
        .computeIfAbsent(new RegistryKey(workflowModuleId, bpmnProcessId), key -> new RegistryEntry());
    synchronized (entry) {
      // one by one, so two methods of the SAME class serving one start event are
      // compared against each other as well
      handlers
          .forEach(handler -> {
            failOnDuplicateWiring(
                workflowModuleId,
                bpmnProcessId,
                entry,
                handler,
                io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.NO_RESOLVER);
            entry.handlers.add(handler);
          });
    }

  }

  /**
   * Resolves the version tags the methods of the given workflow module name and checks
   * the version ranges naming a tag for overlaps - those could not be placed while
   * registering, since no BPMS had been asked about its versions at that point.
   *
   * @param workflowModuleId The workflow module ID
   */
  public void resolveProcessVersions(
      final String workflowModuleId) {

    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> {
          final var bpmnProcessId = entry.getKey().bpmnProcessId();
          final var registryEntry = entry.getValue();
          if (registryEntry.handlers.stream().allMatch(handler -> handler.versionTags().isEmpty())) {
            return;
          }
          processVersions.warmUp(workflowModuleId, bpmnProcessId);
          final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
          synchronized (registryEntry) {
            registryEntry.handlers
                .forEach(handler -> handler
                    .versionTags()
                    .forEach(tag -> processVersions.reportUnknownVersionTag(
                        workflowModuleId,
                        bpmnProcessId,
                        tag,
                        handler.describe())));
            registryEntry.handlers
                .forEach(handler -> failOnDuplicateWiring(
                    workflowModuleId,
                    bpmnProcessId,
                    registryEntry,
                    handler,
                    resolver));
          }
        });

  }

  private static void failOnDuplicateWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RegistryEntry entry,
      final BpmsInitiatedStartHandler handler,
      final io.vanillabp.integration.adapter.migration.workflowtask.VersionRange.ProcessVersionResolver resolver) {

    final var duplicate = entry.handlers
        .stream()
        .filter(existing -> existing != handler)
        .filter(existing -> java.util.Objects.equals(existing.getStartEventId(), handler.getStartEventId()))
        // overlapping version ranges are ambiguous; disjoint ones are a legitimate
        // way to serve several process versions
        .filter(existing -> existing.overlapsVersions(handler, resolver))
        .findFirst();
    if (duplicate.isPresent()) {
      throw new IllegalStateException(
          """
              The @WorkflowStartedByBpms methods '%s' and '%s' both serve %s of BPMN process '%s' of \
              workflow module '%s'! Remove one of them, name the start events they serve by \
              @WorkflowStartedByBpms(id = ...) or distinguish them by version."""
              .formatted(
                  duplicate.get().describe(),
                  handler.describe(),
                  handler.describeWiring(),
                  bpmnProcessId,
                  workflowModuleId));
    }

  }

  /**
   * Registers the start events an adapter reported while wiring a deployed BPMN
   * process and validates the application's methods against them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param startEvents The BPMS-initiated start events of the process
   * @throws IllegalStateException If a method serves a process without such a start
   *           event, or names a start event the process does not have
   */
  public void validate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmsInitiatedStartSpec> startEvents) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      // no application method for this process: the core builds the aggregate on
      // its own, which is the whole point of the hook being optional
      return;
    }
    synchronized (entry) {
      startEvents
          .stream()
          .filter(spec -> entry.startEvents
              .stream()
              .noneMatch(known -> known.elementId().equals(spec.elementId())))
          .forEach(entry.startEvents::add);

      if (entry.startEvents.isEmpty()) {
        throw new IllegalStateException(
            """
                The @WorkflowStartedByBpms method(s) %s serve BPMN process '%s' of workflow module \
                '%s', but that process has no start event the BPMS fires on its own (timer, signal or \
                conditional)! Either the model is missing such a start event, or the method belongs \
                to another process - a workflow started by the application gets its aggregate from \
                ProcessService#startWorkflow."""
                .formatted(
                    describeHandlers(entry.handlers),
                    bpmnProcessId,
                    workflowModuleId));
      }

      entry.handlers
          .stream()
          .filter(handler -> handler.getStartEventId() != null)
          .filter(handler -> entry.startEvents
              .stream()
              .noneMatch(spec -> spec.elementId().equals(handler.getStartEventId())))
          .findFirst()
          .ifPresent(handler -> {
            throw new IllegalStateException(
                """
                    The @WorkflowStartedByBpms method '%s' serves start event '%s' of BPMN process '%s' \
                    of workflow module '%s', but that process has no such start event fired by the \
                    BPMS! Its BPMS-initiated start events are: %s."""
                    .formatted(
                        handler.describe(),
                        handler.getStartEventId(),
                        bpmnProcessId,
                        workflowModuleId,
                        describeStartEvents(entry.startEvents)));
          });
    }

  }

  /**
   * Builds the workflow aggregate of a workflow the BPMS started on its own.
   *
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   * @return The aggregate's ID and the variables the adapter writes back
   */
  public <A> BpmsInitiatedStartResult start(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context,
      final TransactionRunner transactionRunner) {

    final var entry = entries
        .get(
            new RegistryKey(processService.getWorkflowModuleId(), processService.getBpmnProcessId()));
    final var handler = entry == null
        ? null
        : entry.handlers
            .stream()
            .filter(candidate -> candidate.matchesStartEvent(context.getStartEventId()))
            .filter(candidate -> candidate.matchesVersion(
                context.getProcessVersion(),
                processVersions.resolverFor(
                    processService.getWorkflowModuleId(),
                    processService.getBpmnProcessId())))
            .findFirst()
            .orElse(null);

    final var result = BpmsInitiatedStartExecution.run(processService, handler, context, transactionRunner);
    // the BPMS just built this workflow through us - it holds it
    if (result != null) {
      processService.rememberWorkflowAdapter(result.workflowAggregateId(), context.getAdapterId());
    }
    return result;

  }

  private static String describeHandlers(
      final List<BpmsInitiatedStartHandler> handlers) {

    return handlers
        .stream()
        .map(handler -> "'%s' (%s)".formatted(handler.describe(), handler.describeWiring()))
        .collect(Collectors.joining(", "));

  }

  private static String describeStartEvents(
      final List<BpmsInitiatedStartSpec> startEvents) {

    return startEvents
        .stream()
        .map(spec -> "'%s' (%s)".formatted(spec.elementId(), spec.kind()))
        .collect(Collectors.joining(", "));

  }

}
