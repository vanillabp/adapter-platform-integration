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
import io.vanillabp.integration.adapter.migration.workflowend.WorkflowEndedHandlers;
import io.vanillabp.integration.adapter.migration.workflowstart.BpmsInitiatedStarts;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * The core-owned registry of the annotated handler methods of the application per
 * (workflow module, BPMN process ID), and the implementation of the adapter-facing
 * {@link WorkflowTaskInvoker} and {@link BpmsInitiatedStartInvoker}. The platform
 * integration registers every workflow service class under all BPMN process IDs it
 * declares ({@code @WorkflowService.bpmnProcess} and {@code secondaryBpmnProcesses});
 * adapters validate the wiring during <code>wireBpmn</code> and dispatch task
 * invocations at runtime.
 * <p>
 * <code>&#64;WorkflowTask</code> methods are held here; the
 * <code>&#64;WorkflowStartedByBpms</code> methods of the same classes are held by
 * {@link BpmsInitiatedStarts} and the <code>&#64;WorkflowEnded</code> methods by
 * {@link WorkflowEndedHandlers}, to which the other two interfaces delegate. Both
 * mechanisms scan the same classes and share the value conversion - generalizing
 * them into ONE pluggable handler contract is the subject of the extension-enablement
 * story.
 */
public class WorkflowTaskRegistry implements WorkflowTaskInvoker, BpmsInitiatedStartInvoker, WorkflowEndedInvoker {

  private static final Logger log = LoggerFactory.getLogger(WorkflowTaskRegistry.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private static class RegistryEntry {

    private final List<WorkflowTaskHandler> handlers = new LinkedList<>();

    private final List<Class<?>> workflowServiceClasses = new LinkedList<>();

    private MigrationProcessService<?> processService;

    /**
     * Whether {@link #validateTaskWiring} ran for this (module, process) - i.e.
     * an adapter wired a deployed BPMN process against these handlers.
     */
    private volatile boolean wiringValidated = false;

  }

  private final TransactionRunner transactionRunner;

  /**
   * The core's sync model - used to answer
   * {@link #syncedWorkflowAggregateValues(String, String, String, io.vanillabp.integration.adapter.spi.AggregateSyncMode)}
   * for adapters holding no aggregate, and to VALIDATE the model of every
   * registered workflow-aggregate class at startup. May be <code>null</code>
   * (tests): no values are then shared and nothing is validated.
   */
  private final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync;

  private final Map<RegistryKey, RegistryEntry> entries = new ConcurrentHashMap<>();

  /**
   * What the BPMS know about the deployed versions of the BPMN processes - needed to
   * place a version TAG named by a <code>version</code> attribute in the deployment
   * order (story 48). Shared by all three handler kinds, since all three annotations
   * carry that attribute.
   */
  private final ProcessVersions processVersions = new ProcessVersions();

  /**
   * The <code>&#64;WorkflowStartedByBpms</code> methods of the same workflow service
   * classes - what a workflow started by the BPMS itself needs (story 41).
   */
  private final BpmsInitiatedStarts bpmsInitiatedStarts = new BpmsInitiatedStarts(processVersions);

  /**
   * The <code>&#64;WorkflowEnded</code> methods of the same workflow service classes
   * (story 43).
   */
  private final WorkflowEndedHandlers workflowEndedHandlers = new WorkflowEndedHandlers(processVersions);

  /**
   * The transaction annotations of the running platform (story 40b), supplied by the
   * platform integration: which annotations create a transaction boundary here, and
   * which of them this platform does not honor at all. An EMPTY list switches the
   * startup check off, which is what test doubles registering workflow services
   * directly use.
   */
  private final List<TransactionAnnotationSpec> transactionAnnotations;

  /**
   * How a rollback rule excluding a {@code TaskException} is written on THIS platform,
   * derived from the specs of the annotations it actually honors. Handed to the runtime
   * check, which cannot identify the annotation that marked the transaction (it sits on
   * some bean of the handler's call chain) and therefore names the developer's options.
   */
  private final List<String> rollbackRuleRemedies;

  /**
   * The process versions this application declares obsolete (story 57).
   */
  private final OutfadedProcessVersions outfadedVersions;

  /**
   * Whether the application still serves the OLDER versions the BPMS holds (story 57).
   */
  private final DeployedProcessVersionsCheck deployedVersionsCheck;

  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner) {

    this(transactionRunner, null, List.of());

  }

  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync) {

    this(transactionRunner, aggregateSync, List.of());

  }

  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final List<TransactionAnnotationSpec> transactionAnnotations) {

    this(transactionRunner, aggregateSync, transactionAnnotations, null);

  }

  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    this.transactionRunner = transactionRunner;
    this.aggregateSync = aggregateSync;
    this.transactionAnnotations = transactionAnnotations;
    this.outfadedVersions = new OutfadedProcessVersions(properties);
    this.deployedVersionsCheck = new DeployedProcessVersionsCheck(
        processVersions, outfadedVersions, this::tasksNotServedInVersion, this::handlersNotServingAnyVersion);
    this.rollbackRuleRemedies = transactionAnnotations
        .stream()
        .filter(TransactionAnnotationSpec::honored)
        .map(TransactionAnnotationSpec::remedy)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();

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

    // STARTUP validation of the sync model (story 28b): an aggregate whose
    // attributes are annotated both ways without the class stating its own mode is
    // ambiguous - the developer learns it when the application boots, not when the
    // first workflow reaches a sync point
    if (aggregateSync != null) {
      aggregateSync.validateSyncModel(processService.getWorkflowAggregateClass());
    }

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
          beanResolver,
          transactionAnnotations);
      // one by one, so two methods of the SAME class wired to one task definition are
      // compared against each other as well (story 48)
      handlers
          .forEach(handler -> {
            failOnDuplicateWiring(
                workflowModuleId,
                bpmnProcessId,
                entry,
                handler,
                VersionRange.NO_RESOLVER);
            entry.handlers.add(handler);
          });
      entry.workflowServiceClasses.add(workflowServiceClass);
      entry.processService = processService;
    }

    bpmsInitiatedStarts
        .registerWorkflowService(
            workflowModuleId,
            bpmnProcessId,
            workflowServiceClass,
            processService.getWorkflowAggregateClass(),
            workflowServiceBean);

    workflowEndedHandlers
        .registerWorkflowService(
            workflowModuleId,
            bpmnProcessId,
            workflowServiceClass,
            processService.getWorkflowAggregateClass(),
            workflowServiceBean);

  }

  private static void failOnDuplicateWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RegistryEntry entry,
      final WorkflowTaskHandler handler,
      final VersionRange.ProcessVersionResolver resolver) {

    final var duplicate = entry.handlers
        .stream()
        .filter(existing -> existing != handler)
        .filter(existing -> sameWiring(existing.getTaskDefinition(),
            handler.getTaskDefinition()) || sameWiring(existing.getActivityId(), handler.getActivityId()))
        // overlapping version ranges are ambiguous; disjoint ones are a legitimate
        // way to serve several process versions ('1-2' next to '>2' is fine, '1-3'
        // next to '2' is not)
        .filter(existing -> existing.overlapsVersions(handler, resolver))
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
    if (entry != null) {
      entry.wiringValidated = true;
    }

    // mark every matched handler as wired - the reverse direction (methods
    // matching no task of ANY process of the module) is validated per module via
    // validateNoUnwiredWorkflowTaskMethods after all processes were wired
    handlers
        .stream()
        .filter(handler -> tasks.stream().anyMatch(task -> matches(handler, task)))
        .forEach(WorkflowTaskHandler::markWired);

    // OPTIONAL tasks (user tasks - story 24) never fail the validation: a user
    // task without a notification handler is processed through forms/task lists;
    // matching handlers were still marked wired above
    final var unmatchedTasks = tasks
        .stream()
        .filter(task -> !task.optional())
        .filter(task -> handlers.stream().noneMatch(handler -> matches(handler, task)))
        .toList();
    if (unmatchedTasks.isEmpty()) {
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

  @Override
  public void validateNoUnwiredWorkflowTaskMethods(
      final String workflowModuleId) {

    // a method may be registered under several BPMN processes (secondary
    // processes, several handler objects) - it is unwired only if NO
    // registration of it was matched by any wired process
    final var wiredMethods = new java.util.HashSet<String>();
    final var validatedMethods = new java.util.HashSet<String>();
    final var unwiredByMethod = new java.util.LinkedHashMap<String, WorkflowTaskHandler>();
    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> entry
            .getValue().handlers
            .forEach(handler -> {
              final var method = handler.describe();
              if (entry.getValue().wiringValidated) {
                validatedMethods.add(method);
              }
              if (handler.isWired() || servesOnlyOlderVersions(entry.getKey(), handler)) {
                wiredMethods.add(method);
              } else {
                unwiredByMethod.putIfAbsent(method, handler);
              }
            }));
    wiredMethods.forEach(unwiredByMethod::remove);
    // a method whose class' BPMN processes were ALL never wired (no BPMN deployed
    // - e.g. it arrives later during a migration) is not a defect: only methods
    // of at least one actually-wired process are reported
    unwiredByMethod.keySet().retainAll(validatedMethods);
    if (unwiredByMethod.isEmpty()) {
      return;
    }
    final var message = new StringBuilder(
        "@WorkflowTask methods of workflow module '%s' matching no task of any wired BPMN process:"
            .formatted(workflowModuleId));
    unwiredByMethod
        .values()
        .forEach(handler -> message.append(
            """

                  - method '%s' (wired by %s): fix the annotation's taskDefinition/id, remove the \
                annotation or add the task to one of the class' BPMN processes."""
                .formatted(handler.describe(), handler.describeWiring())));
    throw new IllegalStateException(message.toString());

  }

  /**
   * Whether the method exists for OLDER versions of its process only (story 57) - it
   * then matches no task of the model this boot deployed, and that is the point of it
   * rather than a defect.
   * <p>
   * Without this, keeping a method for a version the BPMS still holds would be
   * impossible: the reverse direction would demand that the deployed model still
   * carries the task the newer model dropped, so an application could only serve an
   * old version by keeping a dead task in its current BPMN.
   */
  private boolean servesOnlyOlderVersions(
      final RegistryKey key,
      final WorkflowTaskHandler handler) {

    final var deployedVersions = processVersions
        .registeredCatalogs(key.workflowModuleId(), key.bpmnProcessId())
        .stream()
        .map(registered -> processVersions
            .deployedVersion(registered.adapterId(), key.workflowModuleId(), key.bpmnProcessId()))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (deployedVersions.isEmpty()) {
      // no BPMS counting versions: nothing to exempt, and the reverse direction
      // stays as strict as it was
      return false;
    }
    final var resolver = processVersions.resolverFor(key.workflowModuleId(), key.bpmnProcessId());
    // a method serving the version deployed by ANY of the BPMS is expected to match
    // a task of that model - only a method excluded everywhere is an old-version one
    return deployedVersions
        .stream()
        .noneMatch(version -> handler.matchesVersion(version, resolver));

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
        .filter(candidate -> candidate.matchesVersion(
            context.getProcessVersion(),
            processVersions.resolverFor(workflowModuleId, bpmnProcessId)))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                No @WorkflowTask method of BPMN process '%s' of workflow module '%s' matches task \
                definition '%s' (process version '%s')!%s%s Registered methods: %s."""
                .formatted(
                    bpmnProcessId,
                    workflowModuleId,
                    context.getTaskDefinition(),
                    context.getProcessVersion(),
                    VersionRange.noVersionReportedHint(context.getProcessVersion()),
                    // story 57: a delivery from a version the configuration faded out
                    // looks exactly like a wiring defect otherwise
                    outfadedVersionHint(
                        workflowModuleId, bpmnProcessId, context.getAdapterId(), context.getProcessVersion()),
                    entry.handlers
                        .stream()
                        .map(candidate -> "'%s' (%s)".formatted(candidate.describe(), candidate.describeWiring()))
                        .collect(Collectors.joining(", ")))));
    return entry.processService.executeWorkflowTask(
        handler,
        context,
        transactionRunner,
        rollbackRuleRemedies);

  }

  @Override
  public void registerProcessVersions(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog catalog) {

    processVersions.register(adapterId, workflowModuleId, bpmnProcessId, catalog);

  }

  @Override
  public void resolveProcessVersions(
      final String workflowModuleId) {

    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> {
          final var bpmnProcessId = entry.getKey().bpmnProcessId();
          final var registryEntry = entry.getValue();
          final var tagsUsed = registryEntry.handlers
              .stream()
              .anyMatch(handler -> !handler.versionTags().isEmpty());
          if (!tagsUsed) {
            // story 57: the older versions are checked even where no annotation
            // names a tag, so this is no longer the end of the story
            deployedVersionsCheck.check(workflowModuleId, bpmnProcessId);
            return;
          }
          // the BPMS is asked ONCE per process, right after the deployment: the
          // version deployed by this boot is part of the answer, and a tag the
          // application names is either known now or reported as unknown
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
            // ranges naming a tag could not be placed while registering - now they can
            registryEntry.handlers
                .forEach(handler -> failOnDuplicateWiring(
                    workflowModuleId,
                    bpmnProcessId,
                    registryEntry,
                    handler,
                    resolver));
          }
          deployedVersionsCheck.check(workflowModuleId, bpmnProcessId);
        });

    bpmsInitiatedStarts.resolveProcessVersions(workflowModuleId);
    workflowEndedHandlers.resolveProcessVersions(workflowModuleId);

  }

  @Override
  public void validateBpmsInitiatedStarts(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmsInitiatedStartSpec> startEvents) {

    bpmsInitiatedStarts.validate(workflowModuleId, bpmnProcessId, startEvents);

  }

  @Override
  public BpmsInitiatedStartResult startWorkflowByBpms(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmsInitiatedStartContext context) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      throw new IllegalStateException(
          """
              No @WorkflowService class is registered for BPMN process '%s' of workflow module \
              '%s' - the BPMS started a workflow of it (start event '%s') but there is nothing to \
              build its workflow aggregate! Known processes: %s."""
              .formatted(
                  bpmnProcessId,
                  workflowModuleId,
                  context.getStartEventId(),
                  entries
                      .keySet()
                      .stream()
                      .map(key -> "'%s' (module '%s')".formatted(key.bpmnProcessId(), key.workflowModuleId()))
                      .collect(Collectors.joining(", "))));
    }

    final var result = bpmsInitiatedStarts.start(entry.processService, context, transactionRunner);
    if ((aggregateSync == null) || (context
        .getAggregateSyncMode() == io.vanillabp.integration.adapter.spi.AggregateSyncMode.NONE)) {
      return result;
    }

    // the aggregate values shared with a remote BPMS (story 28) - read AFTER the
    // start's transaction committed, which is why an embedded BPMS (joining the
    // caller's transaction, sync mode NONE) never gets here
    final var variables = new java.util.LinkedHashMap<>(result.variables());
    variables
        .putAll(
            syncedWorkflowAggregateValues(
                workflowModuleId,
                bpmnProcessId,
                result.workflowAggregateId(),
                context.getAggregateSyncMode()));
    variables.put(result.workflowAggregateIdName(), result.workflowAggregateId());
    return new BpmsInitiatedStartResult(
        result.workflowAggregateId(), result.workflowAggregateIdName(), variables, result.created());

  }

  @Override
  public boolean workflowEndedHandlerExists(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return workflowEndedHandlers.handlerExists(workflowModuleId, bpmnProcessId);

  }

  @Override
  public void workflowEnded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final WorkflowEndedContext context) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      throw new IllegalStateException(
          """
              No @WorkflowService class is registered for BPMN process '%s' of workflow module \
              '%s' - the end of workflow '%s' cannot be reported! Known processes: %s."""
              .formatted(
                  bpmnProcessId,
                  workflowModuleId,
                  context.getWorkflowAggregateId(),
                  entries
                      .keySet()
                      .stream()
                      .map(key -> "'%s' (module '%s')".formatted(key.bpmnProcessId(), key.workflowModuleId()))
                      .collect(Collectors.joining(", "))));
    }

    workflowEndedHandlers.workflowEnded(entry.processService, context, transactionRunner);

  }

  @Override
  public boolean workflowTaskHandlerExists(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return false;
    }
    return entry.handlers
        .stream()
        .anyMatch(candidate -> sameWiring(candidate.getTaskDefinition(),
            taskDefinitionOrActivityId) || sameWiring(candidate.getActivityId(), taskDefinitionOrActivityId));

  }

  /**
   * The sentence a delivery from an OUTFADED version deserves - without it the
   * developer reads "no method matches" and looks for a wiring defect, while the
   * configuration says on purpose that this version is not served any more.
   */
  private String outfadedVersionHint(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String processVersion) {

    if ((adapterId == null) || (processVersion == null)) {
      return "";
    }
    if (!outfadedVersions
        .isOutfaded(
            workflowModuleId,
            bpmnProcessId,
            adapterId,
            processVersion,
            processVersions.resolverFor(workflowModuleId, bpmnProcessId))) {
      return "";
    }
    return " Version '%s' is faded out by '%s', so this application deliberately does not serve it - the workflow has to be completed or migrated, or the version has to be served again."
        .formatted(processVersion, OutfadedProcessVersions.propertyName(adapterId));

  }

  @Override
  public void registerDeployedVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    processVersions.recordDeployedVersion(adapterId, workflowModuleId, bpmnProcessId, version);

  }

  /**
   * Which of the given tasks of ONE deployed version no <code>&#64;WorkflowTask</code>
   * method serves - the version-aware sibling of {@link #validateTaskWiring}, used by
   * the startup check of story 57.
   * <p>
   * Two differences to the wiring validation, and both matter. It asks per VERSION, so
   * a method carrying <code>version = "3"</code> counts for version 3 and for nothing
   * else. And it marks NOTHING as wired: serving an old version says nothing about the
   * reverse direction {@link #validateNoUnwiredWorkflowTaskMethods} decides, and a
   * method kept only for an old version must still match a task of the deployed model.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version identifier the BPMS reported
   * @param tasks The tasks of that version's model
   * @return The tasks no method serves in that version
   */
  /**
   * The methods of that BPMN process which serve NO version worth serving - the
   * versions the BPMS holds, minus the ones the configuration faded out (story 57).
   * All three annotations carry a <code>version</code> attribute, so all three are
   * asked.
   * <p>
   * Such a method never runs. It is a warning rather than a boot failure, because a
   * version which does not exist YET is a normal state: an application may be rolled
   * out before the model that needs it, and during a rolling deployment another node
   * may still be deploying it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param servableVersions The versions worth serving
   * @param resolver Resolves version tags of that process
   * @return One description per dead method
   */
  public List<String> handlersNotServingAnyVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> servableVersions,
      final VersionRange.ProcessVersionResolver resolver) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    final var tasks = entry == null
        ? List.<String>of()
        : List
            .copyOf(entry.handlers)
            .stream()
            .filter(handler -> servableVersions
                .stream()
                .noneMatch(version -> handler.matchesVersion(version, resolver)))
            .map(handler -> "@WorkflowTask method '%s' (version %s)"
                .formatted(handler.describe(), handler.describeVersions()))
            .toList();
    return java.util.stream.Stream
        .of(
            tasks,
            bpmsInitiatedStarts.handlersNotServing(workflowModuleId, bpmnProcessId, servableVersions, resolver),
            workflowEndedHandlers.handlersNotServing(workflowModuleId, bpmnProcessId, servableVersions, resolver))
        .flatMap(List::stream)
        .toList();

  }

  public Collection<BpmnTaskSpec> tasksNotServedInVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final Collection<BpmnTaskSpec> tasks) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    final var handlers = entry == null
        ? List.<WorkflowTaskHandler>of()
        : List.copyOf(entry.handlers);
    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    return tasks
        .stream()
        // a user task without a handler is processed through forms or task lists, in
        // an old version exactly as in the deployed one
        .filter(task -> !task.optional())
        .filter(task -> handlers
            .stream()
            .noneMatch(handler -> matches(handler, task) && handler.matchesVersion(version, resolver)))
        .toList();

  }

  @Override
  public boolean workflowTaskCompletesAsynchronously(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return false;
    }
    // ONE method wanting to keep the task open is enough - see the SPI: methods
    // serving different process versions share the BPMN element, and the element
    // either can stay open or it cannot
    return entry.handlers
        .stream()
        .filter(candidate -> sameWiring(candidate.getTaskDefinition(),
            taskDefinitionOrActivityId) || sameWiring(candidate.getActivityId(), taskDefinitionOrActivityId))
        .anyMatch(WorkflowTaskHandler::isAsynchronousTask);

  }

  @Override
  public String resolveWorkflowAggregateIdName(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      throw new IllegalStateException(
          """
              No @WorkflowService class is registered for BPMN process '%s' of workflow module \
              '%s' - the aggregate-ID variable name cannot be determined! Known processes: %s."""
              .formatted(
                  bpmnProcessId,
                  workflowModuleId,
                  entries
                      .keySet()
                      .stream()
                      .map(key -> "'%s' (module '%s')".formatted(key.bpmnProcessId(), key.workflowModuleId()))
                      .collect(Collectors.joining(", "))));
    }
    return entry.processService.getAggregateIdName();

  }

  @Override
  public Map<String, Object> syncedWorkflowAggregateValues(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    if (aggregateSync == null) {
      return Map.of();
    }
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      log.warn(
          """
              No @WorkflowService class is registered for BPMN process '{}' of workflow module \
              '{}' - the values shared with the BPMS cannot be determined; only the technical \
              aggregate-ID variable is sent.""",
          bpmnProcessId,
          workflowModuleId);
      return Map.of();
    }
    // the caller's transaction (the one the @WorkflowTask ran in) is COMMITTED at
    // this point - the aggregate is loaded in a new one. A failure must never
    // prevent the task from being completed: the BPMS would redeliver it forever.
    try {
      return transactionRunner.requireNew(() -> {
        final var workflowAggregate = entry.processService.loadWorkflowAggregate(workflowAggregateId);
        if (workflowAggregate == null) {
          log.warn(
              "The workflow aggregate '{}' of BPMN process '{}' of workflow module '{}' could not "
                  + "be loaded - only the technical aggregate-ID variable is sent to the BPMS",
              workflowAggregateId,
              bpmnProcessId,
              workflowModuleId);
          return Map.<String, Object>of();
        }
        return aggregateSync.syncedValues(workflowAggregate, adapterDefault);
      });
    } catch (final RuntimeException e) {
      log.warn(
          "Could not determine the values of the workflow aggregate '{}' of BPMN process '{}' of "
              + "workflow module '{}' shared with the BPMS - only the technical aggregate-ID "
              + "variable is sent",
          workflowAggregateId,
          bpmnProcessId,
          workflowModuleId,
          e);
      return Map.of();
    }

  }

  @Override
  public boolean workflowAggregateHasProperty(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String propertyName) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return false;
    }
    return AggregatePropertyReader.has(entry.processService.getWorkflowAggregateClass(), propertyName);

  }

  @Override
  public Object resolveWorkflowAggregateProperty(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String propertyName) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return null;
    }
    // runs within the CALLER's transaction: embedded BPMS evaluate expressions
    // inside an engine transaction, the aggregate has to join it
    final var workflowAggregate = entry.processService.loadWorkflowAggregate(workflowAggregateId);
    if (workflowAggregate == null) {
      return null;
    }
    return AggregatePropertyReader.read(workflowAggregate, propertyName);

  }

}
