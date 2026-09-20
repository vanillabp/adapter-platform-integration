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

import io.vanillabp.integration.adapter.migration.processservice.BusinessKeyCheck;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowend.WorkflowEndedHandlers;
import io.vanillabp.integration.adapter.migration.workflowstart.BpmsInitiatedStarts;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.OpenTaskProbe;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The core-owned registry of the annotated handler methods of the application per
 * (workflow module, BPMN process ID), and the implementation of the adapter-facing
 * {@link WorkflowTaskWiring}, {@link WorkflowTaskInvoker} and
 * {@link BpmsInitiatedStartInvoker}. The platform
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
public class WorkflowTaskRegistry implements WorkflowTaskWiring, WorkflowTaskInvoker, BpmsInitiatedStartInvoker, WorkflowEndedInvoker, DeclaredBpmnProcesses {

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
   * The BPMN processes an adapter wired although no <code>&#64;WorkflowService</code>
   * class of the application claims them. A set, so the same process wired by a second
   * adapter of the workflow module is reported once.
   */
  private final java.util.Set<RegistryKey> processesWithoutWorkflowService = ConcurrentHashMap.newKeySet();

  /**
   * What the BPMS know about the deployed versions of the BPMN processes - needed to
   * place a version TAG named by a <code>version</code> attribute in the deployment
   * order. Shared by all three handler kinds, since all three annotations
   * carry that attribute.
   */
  private final ProcessVersions processVersions = new ProcessVersions();

  /**
   * The <code>&#64;WorkflowStartedByBpms</code> methods of the same workflow service
   * classes - what a workflow started by the BPMS itself needs.
   */
  private final BpmsInitiatedStarts bpmsInitiatedStarts = new BpmsInitiatedStarts(processVersions, this);

  /**
   * The <code>&#64;WorkflowEnded</code> methods of the same workflow service classes.
   */
  private final WorkflowEndedHandlers workflowEndedHandlers = new WorkflowEndedHandlers(processVersions);

  /**
   * The methods of the same workflow service classes which belong to an extension's own
   * annotation. Empty in an application without extensions - the scan only happens for
   * contracts somebody registered.
   */
  private final io.vanillabp.integration.adapter.migration.handler.ExtensionHandlerRegistry extensionHandlers;

  /**
   * The transaction annotations of the running platform, supplied by the
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
   * The hint about BPMN processes producing concurrent tokens while their workflow
   * aggregate has no version attribute.
   */
  private final io.vanillabp.integration.adapter.migration.transaction.ConcurrentTokenCheck concurrentTokenCheck = new io.vanillabp.integration.adapter.migration.transaction.ConcurrentTokenCheck();

  /**
   * The bound <code>vanillabp.*</code> tree - needed to answer whether a workflow module
   * releases the records of its processed task deliveries when a workflow ends. May be
   * <code>null</code> (tests): nothing is released then.
   */
  private final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties;

  /**
   * The workflow service classes whose handler methods were already looked over for the
   * two ways of writing one which nobody can see (see {@link HandlerMethodsNobodySees}).
   * A class arrives here once per BPMN process it declares, and the report is about the
   * class rather than about the process, so it is written once.
   */
  private final java.util.Set<Class<?>> classesLookedOverForInvisibleHandlers = java.util.concurrent.ConcurrentHashMap
      .newKeySet();

  /**
   * The refusal to start a workflow which hands its entire aggregate to the BPMS without
   * saying so.
   */
  private final io.vanillabp.integration.adapter.migration.sync.FullSyncCheck fullSyncCheck;

  /**
   * The process versions this application declares obsolete.
   */
  private final OutfadedProcessVersions outfadedVersions;

  /**
   * Whether the application still serves the OLDER versions the BPMS holds.
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

    this(transactionRunner, aggregateSync, transactionAnnotations, properties, null);

  }

  /**
   * @param transactionRunner The transaction every handler runs in
   * @param aggregateSync The core's sync model
   * @param transactionAnnotations The transaction annotations of the platform
   * @param properties The VanillaBP configuration
   * @param scoping The core's name-clash-avoidance support, which is where the identifiers
   *          of a version a BPMS still holds are held against what this deployment
   *          declares. Without it that one check stays silent; everything else works as it
   *          does with it
   */
  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this.transactionRunner = transactionRunner;
    this.extensionHandlers = new io.vanillabp.integration.adapter.migration.handler.ExtensionHandlerRegistry(
        transactionRunner, processVersions);
    this.aggregateSync = aggregateSync;
    this.transactionAnnotations = transactionAnnotations;
    this.properties = properties;
    this.fullSyncCheck = new io.vanillabp.integration.adapter.migration.sync.FullSyncCheck(aggregateSync, properties);
    this.outfadedVersions = new OutfadedProcessVersions(properties);
    this.deployedVersionsCheck = new DeployedProcessVersionsCheck(
        processVersions, outfadedVersions, this::tasksNotServedInVersion, this::handlersNotServingAnyVersion, this, this::reportConcurrentTokenElementsOfHeldVersions, scoping == null
            ? null
            : scoping::reportIdentifiersOfHeldVersion);
    this.rollbackRuleRemedies = transactionAnnotations
        .stream()
        .filter(TransactionAnnotationSpec::honored)
        .map(TransactionAnnotationSpec::remedy)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();

  }

  /**
   * The name of the aggregate's ID attribute, which an application's own persistence
   * does not have to answer: the name is needed by adapters storing the ID in a process
   * variable, and an application not using one never implements it. Asked here so that
   * the full-sync check can leave the ID out of what an aggregate gives away - it
   * reaches the BPMS whatever the sync model says.
   *
   * @param processService The process service of the BPMN process
   * @return The name, or <code>null</code> where the persistence does not answer one
   */
  private static String aggregateIdAttributeOf(
      final MigrationProcessService<?> processService) {

    try {
      return processService.getAggregateIdName();
    } catch (final RuntimeException persistenceDoesNotNameIt) {
      log.debug(
          "The persistence of '{}' does not name the aggregate's ID attribute: {}",
          processService.getWorkflowAggregateClass().getName(),
          persistenceDoesNotNameIt.getMessage());
      return null;
    }

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

    // STARTUP validation of the sync model: an aggregate whose
    // attributes are annotated both ways without the class stating its own mode is
    // ambiguous, and an attribute shared as a type whose text carries no value is
    // refused - the developer learns both when the application boots, not when the
    // first workflow reaches a sync point
    if (aggregateSync != null) {
      aggregateSync.validateSyncModel(processService.getWorkflowAggregateClass());
    }
    // the second half of the same moment: an aggregate which keeps NOTHING back hands
    // every attribute to the BPMS, and this workflow has to have said that it may
    fullSyncCheck.refuseSharingEverythingUnlessAllowed(
        workflowModuleId,
        bpmnProcessId,
        workflowServiceClass,
        processService.getWorkflowAggregateClass(),
        aggregateIdAttributeOf(processService));

    reportHandlerMethodsNobodySees(workflowServiceClass);

    // which generation of the model this class serves for THIS process - the methods
    // naming no version of their own serve it, which is how one class per generation
    // works without repeating the range on every method
    final var inherited = InheritedVersions.declaredFor(workflowServiceClass, bpmnProcessId);
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
          transactionAnnotations,
          inherited);
      // one by one, so two methods of the SAME class wired to one task definition are
      // compared against each other as well
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
            workflowServiceBean,
            inherited);

    workflowEndedHandlers
        .registerWorkflowService(
            workflowModuleId,
            bpmnProcessId,
            workflowServiceClass,
            processService.getWorkflowAggregateClass(),
            workflowServiceBean,
            inherited);

    extensionHandlers
        .registerWorkflowService(
            workflowModuleId,
            bpmnProcessId,
            workflowServiceClass,
            workflowServiceBean,
            beanResolver,
            processService);

  }

  /**
   * The handler methods of the extensions - offered as a bean by both platform
   * integrations, so an extension registers its contracts and invokes its methods
   * through it.
   *
   * @return The registry, never <code>null</code>
   */
  public io.vanillabp.integration.adapter.migration.handler.ExtensionHandlerRegistry getExtensionHandlers() {

    return extensionHandlers;

  }

  /**
   * Writes the report about the handler methods of a workflow service class which the scan
   * cannot reach, once per class. It is a WARN rather than the end of the boot: an
   * application whose BPMN model really carries the task such a method was meant to serve
   * does not start anyway, because the wiring validation asks for a method it does not
   * find, and this is the sentence which says why that method is not there although the
   * developer can read it in their own source.
   *
   * @param workflowServiceClass The class about to be scanned for handlers
   */
  private void reportHandlerMethodsNobodySees(
      final Class<?> workflowServiceClass) {

    if (!classesLookedOverForInvisibleHandlers.add(workflowServiceClass)) {
      return;
    }
    final var report = HandlerMethodsNobodySees
        .reportFor(workflowServiceClass, HandlerMethodsNobodySees.CORE_HANDLER_ANNOTATIONS);
    if (report != null) {
      log.warn(report);
    }

  }

  @Override
  public boolean workflowsShareTheWorkflowAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String otherBpmnProcessId) {

    final var aggregate = workflowAggregateClassOf(workflowModuleId, bpmnProcessId);
    final var otherAggregate = workflowAggregateClassOf(workflowModuleId, otherBpmnProcessId);
    return (aggregate != null) && aggregate.equals(otherAggregate);

  }

  /**
   * The workflow aggregate a BPMN process of a workflow module works on, or
   * <code>null</code> if no workflow service declared that process.
   */
  private Class<?> workflowAggregateClassOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return null;
    }
    return entry.processService.getWorkflowAggregateClass();

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
              The @WorkflowTask methods '%s' (version %s) and '%s' (version %s) are both wired to \
              %s of BPMN process '%s' of workflow module '%s'! Remove one of them or distinguish \
              them by version - on the method by @WorkflowTask(version = ...), or, where a whole \
              class serves one generation of the model, on the class by @BpmnProcess(version = ...)."""
              .formatted(
                  duplicate.get().describe(),
                  duplicate.get().describeVersionsWithOrigin(),
                  handler.describe(),
                  handler.describeVersionsWithOrigin(),
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
  public void reportConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> elementIds) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      // a process no @WorkflowService class serves is reported by the wiring
      // validation already - there is no aggregate class to ask here
      return;
    }
    concurrentTokenCheck
        .reportConcurrentTokenElements(
            workflowModuleId,
            bpmnProcessId,
            entry.processService.getWorkflowAggregateClass(),
            entry.processService.detectsConcurrentModification(),
            elementIds);

  }

  /**
   * The same for the versions a BPMS still holds with workflows running on them, read by the
   * startup check for old process versions - the elements of an older model reach an aggregate
   * exactly as the elements of the deployed one do, and those workflows are the ones which run
   * longest.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param elementIdsByVersion The elements producing a second token, per version identifier
   */
  private void reportConcurrentTokenElementsOfHeldVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, Collection<String>> elementIdsByVersion) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return;
    }
    concurrentTokenCheck
        .reportConcurrentTokenElementsOfHeldVersions(
            workflowModuleId,
            bpmnProcessId,
            entry.processService.getWorkflowAggregateClass(),
            entry.processService.detectsConcurrentModification(),
            elementIdsByVersion);

  }

  @Override
  public void validateTaskWiring(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmnTaskSpec> tasks) {

    // what the modeller wrote on the elements: nothing here decides anything by it, and
    // an extension asking for it would otherwise parse the same BPMN bytes again
    extensionHandlers.rememberBpmnTaskNames(workflowModuleId, bpmnProcessId, tasks);

    final var key = new RegistryKey(workflowModuleId, bpmnProcessId);
    final var entry = entries.get(key);
    if (entry == null) {
      // nobody claimed this process, so there is nothing to validate it against:
      // asking for the @WorkflowTask methods of a process the application never said
      // it serves would end the boot over a model somebody else owns. What it costs
      // is reported once per workflow module by the deployment, see
      // #bpmnProcessesWithoutWorkflowService
      processesWithoutWorkflowService.add(key);
      return;
    }
    final var handlers = List.copyOf(entry.handlers);
    entry.wiringValidated = true;

    // mark every matched handler as wired - the reverse direction (methods
    // matching no task of ANY process of the module) is validated per module via
    // validateNoUnwiredWorkflowTaskMethods after all processes were wired
    handlers
        .stream()
        .filter(handler -> tasks.stream().anyMatch(task -> matches(handler, task)))
        .forEach(WorkflowTaskHandler::markWired);

    // OPTIONAL tasks (user tasks) never fail the validation: a user
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
    // a registered process always has the class which registered it, so the message
    // can name where the missing method belongs
    final var serviceClasses = entry.workflowServiceClasses
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
  public Collection<String> bpmnProcessesWithoutWorkflowService(
      final String workflowModuleId) {

    return processesWithoutWorkflowService
        .stream()
        .filter(key -> key.workflowModuleId().equals(workflowModuleId))
        .map(RegistryKey::bpmnProcessId)
        .sorted()
        .toList();

  }

  /**
   * Says once per workflow module which <code>&#64;WorkflowTask</code> methods would be
   * called with values missing where VanillaBP works a cancellation out for itself.
   * <p>
   * A derived cancellation carries no job of the element, because the element is gone by
   * the time anybody notices it. So a <code>&#64;TaskParam</code> and a multi-instance value
   * reach such a method as <code>null</code>, and a value which is silently absent is the
   * kind of thing an application finds out in production. Only the methods which really
   * declare one are named, and only those which asked for the event at all - a method
   * without a <code>&#64;TaskEvent</code> parameter never sees a cancellation and has
   * nothing to read here.
   */
  @Override
  public void reportWhatACancelationCannotCarry(
      final String workflowModuleId) {

    final var affected = new java.util.LinkedHashMap<String, WorkflowTaskHandler>();
    entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> entry
            .getValue().handlers
            .stream()
            .filter(handler -> handler.acceptsEvent(io.vanillabp.spi.service.TaskEvent.Event.CANCELED))
            .filter(handler -> !handler.getTaskParameters().isEmpty() || !handler
                .getMultiInstanceElementNames()
                .isEmpty())
            .forEach(handler -> affected.putIfAbsent(handler.describe(), handler)));
    if (affected.isEmpty()) {
      return;
    }
    final var message = new StringBuilder(
        ("These @WorkflowTask methods of workflow module '%s' subscribe to "
            + "TaskEvent.Event.CANCELED and read values of the task they are called for. Where "
            + "VanillaBP works the cancellation out itself - the workflow ended, or the BPMS does "
            + "not have the task any more - the element is gone and those values reach the method "
            + "as null:").formatted(workflowModuleId));
    affected
        .values()
        .forEach(handler -> message.append(
            ("%n  - method '%s' reads %s. Check for null there, or read what you need from the "
                + "workflow aggregate, which a cancellation always carries.")
                .formatted(handler.describe(), whatItReads(handler))));
    log.info(message.toString());

  }

  /**
   * What a method reads of the task it is called for, in the words of its annotations.
   */
  private static String whatItReads(
      final WorkflowTaskHandler handler) {

    final var reads = new java.util.LinkedList<String>();
    if (!handler.getTaskParameters().isEmpty()) {
      reads
          .add("@TaskParam %s".formatted(String.join(", ", handler.getTaskParameters())));
    }
    if (!handler.getMultiInstanceElementNames().isEmpty()) {
      reads
          .add(
              "the value of multi-instance element %s"
                  .formatted(String.join(", ", handler.getMultiInstanceElementNames())));
    }
    return String.join(" and ", reads);

  }

  @Override
  public void reportExtensionHandlerWiring(
      final String workflowModuleId) {

    extensionHandlers.reportWiring(workflowModuleId);

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
   * Whether the method exists for OLDER versions of its process only - it
   * then matches no task of the model this boot deployed, and that is the point of it
   * rather than a defect.
   * <p>
   * Without this, keeping a method for a version the BPMS still holds would be
   * impossible: the reverse direction would demand that the deployed model still
   * carries the task the newer model dropped, so an application could only serve an
   * old version by keeping a dead task in its current BPMN.
   * <p>
   * A BPMN process id the application declares WITHOUT bringing a model for it - the id
   * a renamed process left behind - is the same situation with a different boundary:
   * this boot deployed no version of it at all, so the versions the BPMS holds under it
   * are what its methods are kept for.
   */
  private boolean servesOnlyOlderVersions(
      final RegistryKey key,
      final WorkflowTaskHandler handler) {

    if (isDeclaredWithoutDeployment(key.workflowModuleId(), key.bpmnProcessId())) {
      // nothing was deployed under this id, so no model of this boot carries the task
      // this method is wired to - which is what declaring the id says: the method
      // serves what the BPMS still holds under it. A method serving none of those
      // versions is reported, and where the BPMS holds nothing at all the check for old
      // versions says so about the id itself rather than about every method kept for it
      final var heldVersions = processVersions
          .registeredCatalogs(key.workflowModuleId(), key.bpmnProcessId())
          .stream()
          .map(registered -> registered
              .catalog()
              .deployedVersionsOf(key.workflowModuleId(), key.bpmnProcessId()))
          .filter(java.util.Objects::nonNull)
          .flatMap(List::stream)
          .map(io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion::version)
          .filter(java.util.Objects::nonNull)
          .distinct()
          .toList();
      final var resolver = processVersions.resolverFor(key.workflowModuleId(), key.bpmnProcessId());
      return heldVersions.isEmpty() || heldVersions.stream()
          .anyMatch(version -> handler.matchesVersion(version, resolver));
    }
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

  /**
   * Whether this method serves the delivery at hand, asked with BOTH keys a task is wired
   * by. A method names one of them (<code>&#64;WorkflowTask(taskDefinition = ...)</code>
   * respectively <code>&#64;WorkflowTask(id = ...)</code>) and a delivery reports both,
   * the task definition the BPMS subscribed to and the id of the element it belongs to.
   * <p>
   * The pair is the same one <code>validateTaskWiring</code> matches a method against while
   * the application boots, and that is the point: a wiring the boot accepts has to be a
   * wiring a delivery finds. Asking with the task definition alone accepted a method wired
   * by the element id at deployment and failed it at the first delivery, which the
   * application learnt from an incident.
   * <p>
   * The element id is also compared against the reported task definition, for an adapter
   * which names no element of its own: there the one value it reports IS the element id.
   * <p>
   * Why the routing learns the second key instead of the deployment refusing such a wiring
   * is decision 67 in the repository's DECISIONS.md.
   *
   * @param handler One method registered for this BPMN process
   * @param context What the adapter reports about the delivery
   * @return Whether the method serves it
   */
  private static boolean serves(
      final WorkflowTaskHandler handler,
      final TaskInvocationContext context) {

    return sameWiring(handler.getTaskDefinition(), context.getTaskDefinition()) || sameWiring(
        handler.getActivityId(),
        context.getTaskDefinition()) || sameWiring(handler.getActivityId(), context.getBpmnElementId());

  }

  /**
   * How a delivery names its BPMN element in the message about a method nobody found,
   * and nothing where the adapter names none. The two keys are different values on most
   * models, and a reader who only sees the task definition looks for a method under a
   * name their model does not carry.
   *
   * @param context What the adapter reports about the delivery
   * @return The phrase to put after the task definition, possibly empty
   */
  private static String elementIdOfTheDelivery(
      final TaskInvocationContext context) {

    final var elementId = context.getBpmnElementId();
    return ((elementId == null) || elementId.equals(context.getTaskDefinition()))
        ? ""
        : " of BPMN element '%s'".formatted(elementId);

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

    // asked before anything else is done with this delivery: a workflow which names
    // itself twice is not served, whichever method would have served it
    BusinessKeyCheck
        .refuseAKeyWhichIsNotTheAggregateId(
            context.getBusinessKey(),
            context.getWorkflowAggregateId(),
            "The delivery of task '%s'".formatted(context.getTaskDefinition()),
            workflowModuleId,
            bpmnProcessId,
            context.getAdapterId(),
            context.getWorkflowId());

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
        .filter(candidate -> serves(candidate, context))
        .filter(candidate -> candidate.matchesVersion(
            context.getProcessVersion(),
            processVersions.resolverFor(workflowModuleId, bpmnProcessId)))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                No @WorkflowTask method of BPMN process '%s' of workflow module '%s' matches task \
                definition '%s'%s (process version '%s')!%s%s Registered methods: %s."""
                .formatted(
                    bpmnProcessId,
                    workflowModuleId,
                    context.getTaskDefinition(),
                    elementIdOfTheDelivery(context),
                    context.getProcessVersion(),
                    VersionRange.noVersionReportedHint(
                        context.getProcessVersion(),
                        entry.handlers
                            .stream()
                            .anyMatch(WorkflowTaskHandler::inheritsVersions)),
                    // A delivery from a version the configuration faded out
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

  /**
   * Reports the tasks the BPMS does not have any more, asked by an adapter after it handed
   * a delivery over - see
   * {@link WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas(String, String, TaskInvocationContext, OpenTaskProbe)}.
   * <p>
   * What it skips, and in this order, because each step is cheaper than the next: a BPMN
   * process with no asynchronous task at all, which most applications are and which then
   * pay nothing; a scope whose configuration switched the probing off; a delivery which
   * names no workflow; and an application without a delivery log.
   * <p>
   * Nothing here may cost the caller its own work. The delivery is done by the time an
   * adapter asks, and a probe which throws or a derived cancellation which fails must not
   * turn a finished job into a failed one, so both stay inside this call.
   */
  @Override
  public void reportTasksTheBpmsNoLongerHas(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext wakeUp,
      final OpenTaskProbe probe) {

    if ((probe == null) || (wakeUp == null)) {
      return;
    }
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return;
    }
    // a BPMN process whose methods all complete when they return leaves no task open, so
    // there is nothing this could ever find - and that is most applications
    if (entry.handlers.stream().noneMatch(WorkflowTaskHandler::isAsynchronousTask)) {
      return;
    }
    final var howMany = properties == null
        ? io.vanillabp.integration.adapter.migration.config.DeliveryProperties.DEFAULT_MAX_OPEN_TASKS_CHECKED
        : properties.maxOpenTasksChecked(workflowModuleId, bpmnProcessId, wakeUp.getTaskDefinition());
    if (howMany == 0) {
      return;
    }
    final var workflowId = wakeUp.getWorkflowId();
    if ((workflowId == null) || workflowId.isBlank()) {
      return;
    }
    final var deliveryLog = entry.processService.resolveTaskDeliveryLog();
    if (deliveryLog == null) {
      return;
    }

    final var candidates = deliveryLog
        .openTasksOfWorkflow(workflowModuleId, workflowId)
        .stream()
        // the task being worked on right now is not a candidate: it is open because this
        // very delivery left it open
        .filter(record -> !java.util.Objects.equals(record.taskId(), wakeUp.getTaskId()))
        // and a record another BPMS wrote belongs to that BPMS, whose tasks this probe
        // knows nothing about
        .filter(record -> (wakeUp.getAdapterId() == null) || wakeUp.getAdapterId().equals(record.adapterId()))
        .filter(record -> record.taskId() != null)
        .limit(howMany)
        .toList();
    if (candidates.isEmpty()) {
      return;
    }

    final var gone = candidates
        .stream()
        .filter(record -> theBpmsNoLongerHas(record, probe))
        .toList();
    if (gone.isEmpty()) {
      return;
    }
    DerivedCancelations
        .reportAsCanceled(
            gone,
            deliveryLog,
            entry.processService.getTransactionRunner(transactionRunner),
            workflowModuleId,
            this,
            "is gone in its BPMS");

  }

  /**
   * Asks the probe about one record. Only {@link TaskExistence#GONE} produces a
   * cancellation: a probe which cannot say is not a probe which said no, which is the one
   * thing which keeps this safe for a BPMS whose API cannot tell a refusal from an outage
   * (see decision 74 in the repository's DECISIONS.md).
   * <p>
   * A probe which throws is read as "cannot say" and reported once. The delivery which led
   * here is done, and an adapter whose BPMS hiccups must not lose it over a question nobody
   * asked for.
   * <p>
   * The record's task definition travels with the question, because the two ids do not say
   * which kind of task is being asked about and some BPMS answer only for some kinds. A
   * probe which implements the two-argument method alone gets it through the default and
   * decides exactly as it did before.
   */
  private static boolean theBpmsNoLongerHas(
      final TaskDelivery record,
      final OpenTaskProbe probe) {

    final TaskExistence answer;
    try {
      answer = probe.stillExists(record.workflowId(), record.taskId(), record.taskDefinition());
    } catch (final RuntimeException failure) {
      log
          .debug(
              "Asking whether task '{}' of workflow '{}' still exists failed - it is left alone "
                  + "and the next wake-up of that workflow asks again",
              record.taskId(),
              record.workflowId(),
              failure);
      return false;
    }
    return answer == TaskExistence.GONE;

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
  public void reportNoProcessVersionCatalog(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion reported) {

    processVersions.registerWithoutACatalog(adapterId, workflowModuleId, bpmnProcessId, reported);

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
          // a BPMS which said it keeps no catalog has answered every version question of
          // this process, so the methods waiting for an answer are named here and the
          // messages about an unknown version stay out of it
          processVersions
              .reportMethodsWhichNeverRun(
                  workflowModuleId,
                  bpmnProcessId,
                  this::handlersNeverRunningWithoutAVersionCatalog);
          final var tagsUsed = registryEntry.handlers
              .stream()
              .anyMatch(handler -> !handler.versionTags().isEmpty());
          if (!tagsUsed) {
            // the older versions are checked even where no annotation names a tag
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
                        "method '%s'%s"
                            .formatted(handler.describe(), handler.describeVersionOrigin()))));
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

    // a method which never runs is a statement about the whole workflow module, so it
    // is made once every BPMN process of the module was asked about its versions
    deployedVersionsCheck.reportDeadHandlers(workflowModuleId);

    bpmsInitiatedStarts.resolveProcessVersions(workflowModuleId);
    workflowEndedHandlers.resolveProcessVersions(workflowModuleId);
    extensionHandlers.resolveProcessVersions(workflowModuleId);

    // last, because it compares version ranges: an id nothing was deployed under is
    // wired by no adapter, so the start events its methods serve are judged here or
    // nowhere
    bpmsInitiatedStarts.validateAgainstVersionsTheBpmsHolds(workflowModuleId);

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

    final var result = bpmsInitiatedStarts
        .start(entry.processService, context, entry.processService.getTransactionRunner(transactionRunner));
    if ((aggregateSync == null) || (context
        .getAggregateSyncMode() == io.vanillabp.integration.adapter.spi.AggregateSyncMode.NONE)) {
      return result;
    }

    // the aggregate values shared with a remote BPMS - read AFTER the
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

  /**
   * Whether the end of a workflow of that BPMN process has to be reported at all - asked
   * by the adapters while wiring, so a model pays for a listener respectively a worker
   * only where the end is used.
   * <p>
   * Beside an application's <code>&#64;WorkflowEnded</code> method there are two more
   * reasons to want it: a workflow module which releases the records of its processed
   * task deliveries when a workflow ends needs the notification to do so, and so does an
   * election cache which is to let go of the hint of an ended workflow early. That is
   * why no adapter had to be touched for either of them.
   */
  @Override
  public boolean workflowEndedHandlerExists(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (workflowEndedHandlers.handlerExists(workflowModuleId, bpmnProcessId)) {
      return true;
    }
    if (properties == null) {
      return false;
    }
    return properties.releasesDeliveryRecordsOnWorkflowEnd(workflowModuleId) || properties
        .releasesElectionHintsOnWorkflowEnd();

  }

  @Override
  public void workflowEnded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final WorkflowEndedContext context) {

    BusinessKeyCheck
        .refuseAKeyWhichIsNotTheAggregateId(
            context.getBusinessKey(),
            context.getWorkflowAggregateId(),
            "The notification that a workflow ended",
            workflowModuleId,
            bpmnProcessId,
            context.getAdapterId(),
            null);

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

    // the tasks first, each in a transaction of its own, then the end: a workflow which
    // ended has nothing open any more, so everything the application still believes is
    // open in it is gone and is reported as canceled
    reportTheTasksTheEndedWorkflowTookAway(workflowModuleId, entry, context);

    workflowEndedHandlers
        .workflowEnded(entry.processService, context, entry.processService.getTransactionRunner(transactionRunner));

  }

  /**
   * Reports every task the core still believes is open in the ended workflow as
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED}, before the end itself is
   * reported.
   * <p>
   * Only where the adapter named the workflow
   * ({@link WorkflowEndedContext#getWorkflowId()}). An adapter which names none keeps
   * behaving exactly as it did, which is what an adapter whose BPMS cancels each element by
   * itself wants - Camunda 7 would otherwise report the same task twice.
   * <p>
   * A record of ANOTHER adapter is left alone. {@link TaskDelivery#adapterId()} says who
   * delivered the task, so nothing has to be asked: during a migration one aggregate may
   * carry work of two BPMS, and the end this adapter reports says nothing about the other
   * one.
   */
  private void reportTheTasksTheEndedWorkflowTookAway(
      final String workflowModuleId,
      final RegistryEntry entry,
      final WorkflowEndedContext context) {

    final var workflowId = context.getWorkflowId();
    if ((workflowId == null) || workflowId.isBlank()) {
      return;
    }
    final var deliveryLog = entry.processService.resolveTaskDeliveryLog();
    if (deliveryLog == null) {
      return;
    }
    final var openRecords = deliveryLog
        .openTasksOfWorkflow(workflowModuleId, workflowId)
        .stream()
        .filter(record -> (context.getAdapterId() == null) || context.getAdapterId().equals(record.adapterId()))
        .toList();
    if (openRecords.isEmpty()) {
      return;
    }
    DerivedCancelations
        .reportAsCanceled(
            openRecords,
            deliveryLog,
            entry.processService.getTransactionRunner(transactionRunner),
            workflowModuleId,
            this,
            "was still open when its workflow ended");

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

  @Override
  public Collection<String> taskParameterNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return List.of();
    }
    // the UNION over every method serving this element: methods wired to one BPMN
    // element differ in the process version they serve, and the delivery
    // has to satisfy whichever of them runs. Sorted, because a subscription which
    // names variables is compared to itself across restarts
    return entry.handlers
        .stream()
        .filter(candidate -> sameWiring(candidate.getTaskDefinition(),
            taskDefinitionOrActivityId) || sameWiring(candidate.getActivityId(), taskDefinitionOrActivityId))
        .map(WorkflowTaskHandler::getTaskParameters)
        .flatMap(List::stream)
        .distinct()
        .sorted()
        .toList();

  }

  @Override
  public Collection<String> multiInstanceElementNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinitionOrActivityId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return List.of();
    }
    // the UNION over every method serving this element, for the reason
    // taskParameterNames unions its answer: methods wired to one BPMN element differ in
    // the process version they serve, and all of them read their item out of the model
    // the adapter is asking about
    return entry.handlers
        .stream()
        .filter(candidate -> sameWiring(candidate.getTaskDefinition(),
            taskDefinitionOrActivityId) || sameWiring(candidate.getActivityId(), taskDefinitionOrActivityId))
        .map(WorkflowTaskHandler::getMultiInstanceElementNames)
        .flatMap(List::stream)
        .distinct()
        .sorted()
        .toList();

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
   * The methods registered for that BPMN process which serve NO version worth serving -
   * the versions the BPMS holds, minus the ones the configuration faded out.
   * All three annotations carry a <code>version</code> attribute, so all three are
   * asked.
   * <p>
   * The verdict is reached for the WHOLE workflow module, not for the given process
   * alone: a class declares one <code>bpmnProcess</code> plus any number of
   * <code>secondaryBpmnProcesses</code>, so each of its methods is registered several
   * times, once per declared BPMN process and with the version range of that
   * declaration. A method which serves the versions of ANY of them runs, which is why
   * the versions worth serving arrive here per BPMN process of the module. The case
   * this is for is a renamed BPMN process: the methods kept for the old id serve the
   * versions the BPMS holds under it and none of the new id's, and calling them dead
   * would be the opposite of what the declaration says.
   * <p>
   * Such a method never runs. It is a warning rather than a boot failure, because a
   * version which does not exist YET is a normal state: an application may be rolled
   * out before the model that needs it, and during a rolling deployment another node
   * may still be deploying it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID the methods are reported for
   * @param servableVersionsByProcess The versions worth serving, per BPMN process of
   *          that workflow module
   * @return One description per dead method
   */
  public List<String> handlersNotServingAnyVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, Collection<String>> servableVersionsByProcess) {

    return handlerWalksOf(workflowModuleId)
        .stream()
        .flatMap(handlersOf -> deadIn(workflowModuleId, bpmnProcessId, servableVersionsByProcess, handlersOf).stream())
        .toList();

  }

  /**
   * The methods registered for that BPMN process which a delivery of a BPMS without a
   * version catalog can never reach - the version half of
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}.
   * <p>
   * Same four kinds of handler as {@link #handlersNotServingAnyVersion}, same walk, and
   * a different question asked of each method: not "which of the versions the BPMS holds
   * does it serve", because there are none to hold, but "can a delivery of this BPMS meet
   * its specification at all".
   * <p>
   * The verdict is per BPMN process rather than per workflow module, unlike the one about
   * dead methods. What is reported here is true of THIS BPMS, and the message says so, so
   * a method which is the right one on a BPMS the application also runs on is named
   * without being called dead.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param reported What a delivery of that BPMS carries as its process version
   * @return One description per method which never runs there
   */
  public List<String> handlersNeverRunningWithoutAVersionCatalog(
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion reported) {

    return handlerWalksOf(workflowModuleId)
        .stream()
        .flatMap(
            handlersOf -> handlersOf.of(bpmnProcessId, served -> served.canBeMetWithoutACatalog(reported)).stream())
        .filter(handler -> !handler.servesAVersion())
        .map(HandlerVersions::description)
        .toList();

  }

  /**
   * A walk over the methods of ONE kind of handler registered for one BPMN process,
   * answering the question it is handed about each of them.
   */
  @FunctionalInterface
  private interface HandlerWalk {

    List<HandlerVersions> of(
        String bpmnProcessId,
        java.util.function.Predicate<ServedVersions> serves);

  }

  /**
   * The four kinds of handler carrying a <code>version</code> attribute, each as a walk
   * over the methods of one BPMN process of the given workflow module.
   *
   * @param workflowModuleId The workflow module ID
   * @return One walk per kind of handler
   */
  private List<HandlerWalk> handlerWalksOf(
      final String workflowModuleId) {

    return List.of(
        (
            process,
            serves) -> workflowTaskHandlerVersions(workflowModuleId, process, serves),
        (
            process,
            serves) -> bpmsInitiatedStarts.handlerVersions(workflowModuleId, process, serves),
        (
            process,
            serves) -> workflowEndedHandlers.handlerVersions(workflowModuleId, process, serves),
        (
            process,
            serves) -> extensionHandlers.handlerVersions(workflowModuleId, process, serves));

  }

  /**
   * Which methods of one kind of handler are dead in the given BPMN process - dead
   * meaning that they serve no version worth serving there AND none in any other BPMN
   * process of the workflow module they are registered for.
   *
   * @param bpmnProcessId The BPMN process the methods are reported for
   * @param servableVersionsByProcess The versions worth serving, per BPMN process
   * @param handlersOf The walk over one kind of handler
   * @return One description per dead method
   */
  private List<String> deadIn(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, Collection<String>> servableVersionsByProcess,
      final HandlerWalk handlersOf) {

    final var candidates = handlersOf
        .of(
            bpmnProcessId,
            servesOneOf(workflowModuleId, bpmnProcessId, servableVersionsByProcess.getOrDefault(bpmnProcessId, List
                .of())))
        .stream()
        .filter(handler -> !handler.servesAVersion())
        .toList();
    if (candidates.isEmpty()) {
      return List.of();
    }
    final var servingElsewhere = servableVersionsByProcess
        .entrySet()
        .stream()
        .filter(process -> !process.getKey().equals(bpmnProcessId))
        .flatMap(process -> handlersOf
            .of(process.getKey(), servesOneOf(workflowModuleId, process.getKey(), process.getValue()))
            .stream())
        .filter(HandlerVersions::servesAVersion)
        .map(HandlerVersions::method)
        .collect(Collectors.toSet());
    return candidates
        .stream()
        .filter(handler -> !servingElsewhere.contains(handler.method()))
        .map(HandlerVersions::description)
        .toList();

  }

  /**
   * Whether a method serves one of the versions worth serving in a BPMN process - the
   * question behind the report about methods which never run.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID whose version tags are resolved
   * @param servableVersions The versions worth serving there
   * @return The question, ready to be handed to a walk over the methods
   */
  private java.util.function.Predicate<ServedVersions> servesOneOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> servableVersions) {

    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    return served -> servableVersions
        .stream()
        .anyMatch(version -> served.matches(version, resolver));

  }

  /**
   * The <code>&#64;WorkflowTask</code> methods registered for one BPMN process, each with
   * the verdict the given question reaches about its version specifications.
   */
  private List<HandlerVersions> workflowTaskHandlerVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.function.Predicate<ServedVersions> serves) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return List.of();
    }
    return List
        .copyOf(entry.handlers)
        .stream()
        .map(handler -> new HandlerVersions(
            handler.describe(), "@WorkflowTask method '%s' (version %s)"
                .formatted(handler.describe(),
                    handler.describeVersionsWithOrigin()), serves.test(handler.servedVersions())))
        .toList();

  }

  @Override
  public boolean isDeclaredWithoutDeployment(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    return (entry != null) && !entry.wiringValidated;

  }

  @Override
  public Collection<String> deployedProcessesOf(
      final String workflowModuleId) {

    return entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .filter(entry -> entry.getValue().wiringValidated)
        .map(entry -> entry.getKey().bpmnProcessId())
        .toList();

  }

  @Override
  public void registerVersionsOfProcessesNobodyDeployed(
      final String workflowModuleId,
      final String adapterId,
      final java.util.function.BiFunction<String, String, io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog> catalogOfProcess) {

    entriesNobodyDeployed(workflowModuleId)
        .forEach(entry -> processVersions
            .register(
                adapterId,
                workflowModuleId,
                entry.getKey().bpmnProcessId(),
                catalogOfProcess.apply(workflowModuleId, entry.getKey().bpmnProcessId())));

  }

  @Override
  public Map<String, Collection<String>> taskWiringOfProcessesNobodyDeployed(
      final String workflowModuleId) {

    final var servedWiring = new java.util.TreeMap<String, Collection<String>>();
    entriesNobodyDeployed(workflowModuleId)
        .forEach(entry -> servedWiring
            .put(
                entry.getKey().bpmnProcessId(),
                // the task definition is what an adapter can compose its own identifier
                // from. A method wired to a BPMN element id is matched through the model,
                // and the model of that id is the one thing the application does not have,
                // so it contributes nothing - which is why the SPI says that an id may be
                // named with nothing served
                List
                    .copyOf(entry.getValue().handlers)
                    .stream()
                    .map(WorkflowTaskHandler::getTaskDefinition)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList()));
    return servedWiring;

  }

  /**
   * The registry entries of a workflow module which hold <code>&#64;WorkflowTask</code>
   * methods for a BPMN process nothing was deployed under during this boot - the id a
   * renamed BPMN process left behind.
   * <p>
   * It answers fewer ids than the module declares, and both callers want the same fewer:
   * a class serving a BPMN process this boot deployed is a class in play, and a further id
   * IT declares is the renamed process this is about. A class whose processes were NONE of
   * them deployed says nothing about a rename - it is a workflow service waiting for a
   * model which has not arrived yet, and treating every id of it as renamed would bury
   * the one case worth speaking about.
   *
   * @param workflowModuleId The workflow module ID
   * @return The entries, in no particular order
   */
  private List<Map.Entry<RegistryKey, RegistryEntry>> entriesNobodyDeployed(
      final String workflowModuleId) {

    final var entriesOfTheModule = entries
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .toList();
    final var classesInPlay = entriesOfTheModule
        .stream()
        .filter(entry -> entry.getValue().wiringValidated)
        .flatMap(entry -> entry.getValue().workflowServiceClasses.stream())
        .collect(Collectors.toSet());
    return entriesOfTheModule
        .stream()
        .filter(entry -> !entry.getValue().wiringValidated)
        .filter(entry -> entry.getValue().workflowServiceClasses.stream().anyMatch(classesInPlay::contains))
        .toList();

  }

  /**
   * Which of the given tasks of ONE deployed version no <code>&#64;WorkflowTask</code>
   * method serves - the version-aware sibling of {@link #validateTaskWiring}, used by
   * the startup check for old process versions.
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

    return syncedValues(workflowModuleId, bpmnProcessId, workflowAggregateId, adapterDefault, false);

  }

  /**
   * The shared values of an aggregate, read either after the caller's transaction
   * committed (a remote BPMS completing a task) or within it (an embedded engine
   * completing the task in the same transaction).
   */
  private Map<String, Object> syncedValues(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault,
      final boolean inCurrentTransaction) {

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
    // for a remote BPMS the caller's transaction (the one the @WorkflowTask ran in) is
    // COMMITTED at this point, so the aggregate is loaded in a new one; an embedded
    // engine is still inside that transaction and reads there. A failure must never
    // prevent the task from being completed: the BPMS would redeliver it forever.
    final java.util.function.Supplier<Map<String, Object>> read = () -> {
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
    };
    final var runner = entry.processService.getTransactionRunner(transactionRunner);
    try {
      return inCurrentTransaction
          ? runner.inCurrent(read)
          : runner.requireNew(read);
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
  public Map<String, Object> syncedWorkflowAggregateValuesInCurrentTransaction(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    // an embedded engine completes the task in the transaction the handler ran in, so
    // the values have to be read from the aggregate as it is NOW - a new transaction
    // would see the state before the handler or wait for the row this one holds
    return syncedValues(
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId,
        adapterDefault,
        true);

  }

  // the suppression is not a doubt about the deprecation, it is what keeps it quiet:
  // 'forRemoval' raises the mandatory 'removal' lint at every implementation, and
  // @Deprecated on the override does not silence it. Goes when the fallback goes.
  @Deprecated(forRemoval = true)
  @SuppressWarnings("removal")
  @Override
  public boolean workflowAggregateHasProperty(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String propertyName) {

    // the migration fallback: version 1 resolved attributes without a getter
    // as well, so the reader looks at getters AND fields
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return false;
    }
    return AggregatePropertyReader.has(entry.processService.getWorkflowAggregateClass(), propertyName);

  }

  @Deprecated(forRemoval = true)
  @SuppressWarnings("removal")
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
    // runs within the CALLER's transaction: an embedded BPMS evaluates expressions
    // inside an engine transaction, the aggregate has to join it
    final var workflowAggregate = entry.processService.loadWorkflowAggregate(workflowAggregateId);
    if (workflowAggregate == null) {
      return null;
    }
    return AggregatePropertyReader.read(workflowAggregate, propertyName);

  }

  @Override
  public java.util.Collection<String> unsharedWorkflowAggregateProperties(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> names,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    if ((aggregateSync == null) || (names == null) || names.isEmpty()) {
      return List.of();
    }
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return List.of();
    }
    final var aggregateClass = entry.processService.getWorkflowAggregateClass();
    return names
        .stream()
        .distinct()
        .filter(name -> aggregateSync.isAggregateProperty(aggregateClass, name))
        .filter(name -> !aggregateSync.isSharedWithBpms(aggregateClass, name, adapterDefault))
        .toList();

  }

  @Override
  public Map<String, io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict> unsharedWorkflowAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> paths,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    if ((aggregateSync == null) || (paths == null) || paths.isEmpty()) {
      return Map.of();
    }
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return Map.of();
    }
    final var aggregateClass = entry.processService.getWorkflowAggregateClass();
    final var reportable = new java.util.LinkedHashMap<String, io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict>();
    paths
        .stream()
        .distinct()
        .forEach(path -> {
          final var verdict = aggregateSync
              .whatAPathFinds(aggregateClass, List.of(path.split("\\.", -1)), adapterDefault);
          if (!verdict.pathIsCut()) {
            return;
          }
          // the first segment is the one which may be a variable of the model rather
          // than an attribute of the aggregate - the same boundary the question about a
          // single name draws
          if ((verdict
              .kind() == io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict.Kind.NO_SUCH_ATTRIBUTE) && (verdict
                  .segmentIndex() == 0)) {
            return;
          }
          reportable.put(path, verdict);
        });
    return reportable;

  }

  @Override
  public Map<String, Class<?>> declaredTypesOfWorkflowAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.Collection<String> paths,
      final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

    if ((aggregateSync == null) || (paths == null) || paths.isEmpty()) {
      return Map.of();
    }
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      return Map.of();
    }
    final var aggregateClass = entry.processService.getWorkflowAggregateClass();
    final var types = new java.util.LinkedHashMap<String, Class<?>>();
    paths
        .stream()
        .distinct()
        .forEach(path -> aggregateSync
            .whatTypeAPathEndsAt(aggregateClass, List.of(path.split("\\.", -1)), adapterDefault)
            .ifPresent(type -> types.put(path, type)));
    return types;

  }

}
