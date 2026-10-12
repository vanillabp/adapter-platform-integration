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
 * The registry, owned by the core, of the annotated handler methods of the application,
 * per workflow module and BPMN process ID. It also implements the adapter-facing
 * {@link WorkflowTaskWiring}, {@link WorkflowTaskInvoker}, {@link BpmsInitiatedStartInvoker}
 * and {@link WorkflowEndedInvoker}. The platform integration registers every workflow
 * service class under all BPMN process IDs which the class declares
 * ({@code @WorkflowService.bpmnProcess} and {@code secondaryBpmnProcesses}). Adapters check
 * the wiring during <code>wireBpmn</code>, and they dispatch task calls at runtime.
 * <p>
 * This class holds the <code>&#64;WorkflowTask</code> methods. {@link BpmsInitiatedStarts}
 * holds the <code>&#64;WorkflowStartedByBpms</code> methods of the same classes, and
 * {@link WorkflowEndedHandlers} the <code>&#64;WorkflowEnded</code> methods. The two
 * invoker interfaces for starts and ends hand their calls on to those. The methods of the
 * extensions are kept in the
 * {@link io.vanillabp.integration.adapter.migration.handler.ExtensionHandlerRegistry}. All of
 * them are found in the same classes, by the same scan, and share the value conversion.
 */
public class WorkflowTaskRegistry implements WorkflowTaskWiring, WorkflowTaskInvoker, BpmsInitiatedStartInvoker, WorkflowEndedInvoker, DeclaredBpmnProcesses {

  private static final Logger log = LoggerFactory.getLogger(WorkflowTaskRegistry.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  /**
   * One model which an adapter deployed: which adapter, and which BPMN process of which
   * module. The adapter is part of the key, because each adapter of a module deploys and
   * reports its own version.
   */
  private record DeployedModelKey(
                                  String adapterId,
                                  String workflowModuleId,
                                  String bpmnProcessId) {
  }

  private static class RegistryEntry {

    private final List<WorkflowTaskHandler> handlers = new LinkedList<>();

    private final List<Class<?>> workflowServiceClasses = new LinkedList<>();

    private MigrationProcessService<?> processService;

    /**
     * Whether {@link #validateTaskWiring} ran for this module and process. That means an
     * adapter wired a deployed BPMN process against these handlers.
     */
    private volatile boolean wiringValidated = false;

    /**
     * Whether a workflow service class declares this process as its
     * <code>bpmnProcess</code>, and not only in its <code>secondaryBpmnProcesses</code>.
     */
    private volatile boolean declaredAsPrimary = false;

  }

  private final TransactionRunner transactionRunner;

  /**
   * The core's sync model. It answers
   * {@link #syncedWorkflowAggregateValues(String, String, String, io.vanillabp.integration.adapter.spi.AggregateSyncMode)}
   * for adapters which hold no aggregate. And it CHECKS the model of every registered
   * workflow-aggregate class at startup. It may be <code>null</code> in tests. Then no values
   * are shared, and nothing is checked.
   */
  private final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync;

  private final Map<RegistryKey, RegistryEntry> entries = new ConcurrentHashMap<>();

  /**
   * What the BPMS know about the deployed versions of the BPMN processes. It is needed to
   * place a version TAG, named by a <code>version</code> attribute, in the order of the
   * deployments. All four kinds of handler share it: the three annotations of the core and
   * the methods of the extensions. All of them have that attribute.
   */
  private final ProcessVersions processVersions;

  /**
   * The <code>&#64;WorkflowStartedByBpms</code> methods of the same workflow service
   * classes. A workflow which the BPMS itself started needs them.
   */
  private final BpmsInitiatedStarts bpmsInitiatedStarts;

  /**
   * The messages which the adapters reported as starting each deployed BPMN process. The
   * process services check <code>startWorkflowByMessage</code> against them.
   */
  private final io.vanillabp.integration.adapter.migration.workflowstart.StartMessages startMessages;

  /**
   * The <code>&#64;WorkflowEnded</code> methods of the same workflow service classes.
   */
  private final WorkflowEndedHandlers workflowEndedHandlers;

  /**
   * The methods of the same workflow service classes which belong to an extension's own
   * annotation. It is empty in an application without extensions. The scan only happens for
   * contracts which somebody registered.
   */
  private final io.vanillabp.integration.adapter.migration.handler.ExtensionHandlerRegistry extensionHandlers;

  /**
   * The transaction annotations of the running platform, supplied by the platform
   * integration. They say which annotations create a transaction boundary here, and which of
   * them this platform does not honor at all. An EMPTY list switches the startup check off.
   * Test doubles which register workflow services directly use that.
   */
  private final List<TransactionAnnotationSpec> transactionAnnotations;

  /**
   * How a rollback rule which excludes a {@code TaskException} is written on THIS platform.
   * It is derived from the specs of the annotations the platform really honors. It is handed
   * to the runtime check. That check cannot tell which annotation marked the transaction,
   * because it sits on some bean of the handler's call chain. So it names the developer's
   * options.
   */
  private final List<String> rollbackRuleRemedies;

  /**
   * The hint about BPMN processes which produce concurrent tokens while their workflow
   * aggregate has no version attribute.
   */
  private final io.vanillabp.integration.adapter.migration.transaction.ConcurrentTokenCheck concurrentTokenCheck;

  /**
   * The hint about expressions of a BPMN model which are more than the name of a variable.
   */
  private final io.vanillabp.integration.adapter.migration.expressions.ModelExpressionCheck modelExpressionCheck;

  /**
   * Where a finding of this registry goes, so that the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * The bound <code>vanillabp.*</code> tree. It answers whether a workflow module releases
   * the records of its processed task deliveries when a workflow ends. It may be
   * <code>null</code> in tests. Then nothing is released.
   */
  private final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties;

  /**
   * The workflow service classes whose handler methods were already checked for the two
   * ways of writing one which nobody can see (see {@link HandlerMethodsNobodySees}). A class
   * arrives here once for each BPMN process it declares. The report is about the class and
   * not about the process, so it is written once.
   */
  private final java.util.Set<Class<?>> classesLookedOverForInvisibleHandlers = java.util.concurrent.ConcurrentHashMap
      .newKeySet();

  /**
   * The refusal to start a workflow which hands its entire aggregate to the BPMS without
   * saying so.
   */
  private final io.vanillabp.integration.adapter.migration.sync.FullSyncCheck fullSyncCheck;

  /**
   * The second half of the startup verdict about the values of a workflow. The sync model
   * says WHICH of them travel. This check says whether their types survive the way there and
   * back.
   */
  private final PortableValuesCheck portableValuesCheck;

  /**
   * The process versions this application declares obsolete.
   */
  private final OutfadedProcessVersions outfadedVersions;

  /**
   * Whether the application still serves the OLDER versions the BPMS holds.
   */
  private final DeployedProcessVersionsCheck deployedVersionsCheck;

  /**
   * Reads which tasks the application says something else serves.
   */
  private final ImplementedExternallyCheck implementedExternally;

  /**
   * The tasks of every claimed model which an adapter wired during this start. The version
   * which this start deployed is checked against them once the BPMS has said which version
   * that is. That is only known after the wiring.
   */
  private final Map<DeployedModelKey, List<BpmnTaskSpec>> tasksOfDeployedModels = new ConcurrentHashMap<>();

  /**
   * The bare registry: handlers are wired and invoked, and no startup check runs.
   * <p>
   * Everything a platform integration knows is missing here: the sync model, the
   * transaction annotations, the configuration. Every check which depends on one of them
   * stays silent. A test which only needs a delivery to reach a method builds this one.
   *
   * @param transactionRunner The transaction every handler runs in
   */
  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner) {

    this(transactionRunner, null, List.of());

  }

  /**
   * The registry with the sync model. So it decides and checks what leaves for the BPMS.
   *
   * @param transactionRunner The transaction every handler runs in
   * @param aggregateSync The core's sync model. <code>null</code> shares no values and
   *          checks no aggregate class
   */
  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync) {

    this(transactionRunner, aggregateSync, List.of());

  }

  /**
   * The registry which also knows how a transaction is marked on the running platform.
   *
   * @param transactionRunner The transaction every handler runs in
   * @param aggregateSync The core's sync model
   * @param transactionAnnotations The transaction annotations of the platform. An empty list
   *          switches the startup check about them off
   */
  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final List<TransactionAnnotationSpec> transactionAnnotations) {

    this(transactionRunner, aggregateSync, transactionAnnotations, null);

  }

  /**
   * The registry with the configuration. The version and delivery settings are read from
   * it.
   *
   * @param transactionRunner The transaction every handler runs in
   * @param aggregateSync The core's sync model
   * @param transactionAnnotations The transaction annotations of the platform
   * @param properties The VanillaBP configuration. <code>null</code> leaves every setting at
   *          its default
   */
  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    this(transactionRunner, aggregateSync, transactionAnnotations, properties, null);

  }

  /**
   * The full registry, which is what a platform integration builds.
   *
   * @param transactionRunner The transaction every handler runs in
   * @param aggregateSync The core's sync model
   * @param transactionAnnotations The transaction annotations of the platform
   * @param properties The VanillaBP configuration
   * @param scoping The core's name-clash-avoidance support. With it, the identifiers of a
   *          version which a BPMS still holds are checked against what this deployment
   *          declares. Without it that one check stays silent. Everything else works the same
   */
  public WorkflowTaskRegistry(
      final TransactionRunner transactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this.transactionRunner = transactionRunner;
    this.findings = io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties
        .startupFindingsOf(properties);
    // built here and not next to their declaration. Each of them reports into the box, and
    // where the box is only follows from the configuration handed in
    this.processVersions = new ProcessVersions(findings);
    this.bpmsInitiatedStarts = new BpmsInitiatedStarts(processVersions, this);
    this.startMessages = new io.vanillabp.integration.adapter.migration.workflowstart.StartMessages(this);
    this.workflowEndedHandlers = new WorkflowEndedHandlers(processVersions);
    this.concurrentTokenCheck = new io.vanillabp.integration.adapter.migration.transaction.ConcurrentTokenCheck(
        findings);
    this.modelExpressionCheck = new io.vanillabp.integration.adapter.migration.expressions.ModelExpressionCheck(
        findings, properties);
    this.extensionHandlers = new io.vanillabp.integration.adapter.migration.handler.ExtensionHandlerRegistry(
        transactionRunner, processVersions, findings);
    this.aggregateSync = aggregateSync;
    this.transactionAnnotations = transactionAnnotations;
    this.properties = properties;
    this.fullSyncCheck = new io.vanillabp.integration.adapter.migration.sync.FullSyncCheck(aggregateSync, properties);
    this.portableValuesCheck = new PortableValuesCheck(aggregateSync, properties);
    this.outfadedVersions = new OutfadedProcessVersions(properties);
    this.implementedExternally = new ImplementedExternallyCheck(properties, findings);
    this.deployedVersionsCheck = new DeployedProcessVersionsCheck(
        processVersions, outfadedVersions, this::tasksNotServedInVersion, this::handlersNotServingAnyVersion, this, this::reportConcurrentTokenElementsOfHeldVersions, scoping == null
            ? null
            : scoping::reportIdentifiersOfHeldVersion, this::itemsAHeldVersionNeverNames, this::elementsTheDeployedVersionLeavesUnserved, properties == null
                // a registry built without a configuration is a test fixture. It has no start to
                // close either
                ? new io.vanillabp.integration.adapter.migration.startup.StartupFindings()
                : properties.startupFindings());
    this.rollbackRuleRemedies = transactionAnnotations
        .stream()
        .filter(TransactionAnnotationSpec::honored)
        .map(TransactionAnnotationSpec::remedy)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();

  }

  /**
   * The name of the aggregate's ID attribute. An application's own persistence does not
   * have to answer it. Adapters which store the ID in a process variable need the name, and
   * an application which uses no such adapter never implements it. It is asked here so that
   * the full-sync check can leave the ID out of what an aggregate gives away. The ID reaches
   * the BPMS whatever the sync model says.
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
   * Registers all <code>&#64;WorkflowTask</code> methods of the given workflow service class
   * for the given BPMN process. The platform integration calls it at startup, once for each
   * pair of workflow service class and declared BPMN process ID.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowServiceBean Supplies the bean instance of that class. It is resolved
   *          lazily, so that no bean is created at registration time
   * @param beanResolver Resolves beans by class (used for
   *          <code>&#64;MultiInstanceElement(resolverBean = ...)</code>)
   * @param processService The process service of the BPMN process (aggregate persistence and
   *          ID conversion)
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final MigrationProcessService<?> processService) {

    // every process service of a declared id checks its message starts against what the
    // adapters report. That includes one whose class is ignored below, because the
    // application may still call it
    processService.checkStartMessagesAgainst(startMessages);

    // the sync model is checked at STARTUP. An aggregate whose attributes are annotated both
    // ways, without the class stating its own mode, is ambiguous. An attribute which is
    // shared as a type whose text has no value is refused. The developer learns both when
    // the application boots, not when the first workflow reaches a sync point
    if (aggregateSync != null) {
      aggregateSync.validateSyncModel(processService.getWorkflowAggregateClass());
    }
    // the second half of the same moment. An aggregate which keeps NOTHING back hands every
    // attribute to the BPMS, and this workflow has to have said that it may
    fullSyncCheck.refuseSharingEverythingUnlessAllowed(
        workflowModuleId,
        bpmnProcessId,
        workflowServiceClass,
        processService.getWorkflowAggregateClass(),
        aggregateIdAttributeOf(processService));

    // and the third. The sync annotations decided which values leave, so now their TYPES can
    // be judged. That order is the rule, not an accident of the startup sequence. Judged
    // first, the check would judge the types of values which never travel
    portableValuesCheck.refuseValuesWhichDoNotTravelWell(
        workflowModuleId,
        bpmnProcessId,
        workflowServiceClass,
        processService.getWorkflowAggregateClass(),
        aggregateIdAttributeOf(processService),
        processService.getAdaptersOfThisWorkflow());

    reportHandlerMethodsNobodySees(workflowServiceClass);

    // which generation of the model this class serves for THIS process. The methods which
    // name no version of their own serve it. That is how one class per generation works,
    // without repeating the range on every method
    final var inherited = InheritedVersions.declaredFor(workflowServiceClass, bpmnProcessId);
    final var entry = entries.computeIfAbsent(
        new RegistryKey(workflowModuleId, bpmnProcessId),
        key -> new RegistryEntry());
    synchronized (entry) {
      // a BPMN process belongs to exactly one workflow aggregate, see decision 125 in the
      // repository's DECISIONS.md. Both platforms refuse this earlier: Quarkus while it
      // builds, Spring Boot once it knows the workflow modules. So this is the safety net
      // for a platform which does not. Keeping the class found first would let the order of
      // the class scan decide what the process is
      if ((entry.processService != null) && !entry.processService.getWorkflowAggregateClass()
          .equals(processService.getWorkflowAggregateClass())) {
        final var declarations = new java.util.LinkedList<AProcessBelongsToOneAggregate.Declaration>();
        entry.workflowServiceClasses
            .forEach(known -> declarations.add(new AProcessBelongsToOneAggregate.Declaration(
                workflowModuleId, bpmnProcessId, known.getName(), entry.processService.getWorkflowAggregateClass()
                    .getName(), !declaresOnlyAsSecondary(known, bpmnProcessId))));
        declarations.add(new AProcessBelongsToOneAggregate.Declaration(
            workflowModuleId, bpmnProcessId, workflowServiceClass.getName(), processService.getWorkflowAggregateClass()
                .getName(), !declaresOnlyAsSecondary(workflowServiceClass, bpmnProcessId)));
        AProcessBelongsToOneAggregate.refuseProcessesOfSeveralAggregates(declarations);
      }
      final var handlers = WorkflowTaskScanner.scan(
          workflowServiceClass,
          processService.getWorkflowAggregateClass(),
          workflowServiceBean,
          beanResolver,
          transactionAnnotations,
          inherited,
          findings);
      // one by one, so that two methods of the SAME class which are wired to one task
      // definition are compared with each other as well
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
      if (!declaresOnlyAsSecondary(workflowServiceClass, bpmnProcessId)) {
        entry.declaredAsPrimary = true;
      }
    }
    // the adapters report their lists of versions to this registry while they wire their
    // BPMN. The process service needs them to judge a row written without a version
    processService.setProcessVersions(processVersions);

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
   * Whether the class names that process in its <code>secondaryBpmnProcesses</code>, and not
   * as its <code>bpmnProcess</code>. A class without the annotation is a test double which
   * registers handlers directly. It counts as declaring its process as the primary one.
   */
  private static boolean declaresOnlyAsSecondary(
      final Class<?> workflowServiceClass,
      final String bpmnProcessId) {

    final var workflowService = workflowServiceClass.getAnnotation(io.vanillabp.spi.service.WorkflowService.class);
    if ((workflowService == null) || bpmnProcessId.equals(workflowService.bpmnProcess().bpmnProcessId())) {
      return false;
    }
    return java.util.Arrays
        .stream(workflowService.secondaryBpmnProcesses())
        .anyMatch(secondary -> bpmnProcessId.equals(secondary.bpmnProcessId()));

  }

  /**
   * Whether that process is a called process. That is so when the application declares it
   * only as a secondary process, and an adapter deployed a model for it during this boot.
   * Such a process is no workflow of its own. It runs on the workflow aggregate of the
   * process which called it. So its start and its end do not reach the application.
   * <p>
   * A secondary id without a model is the id which a renamed process left behind. The BPMS
   * still holds workflows which were started under that id. So they keep their start and
   * their end. Decision 124 in the repository's DECISIONS.md says why.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Whether an instance of that process is part of the workflow which called it
   */
  public boolean isACalledProcess(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    return (entry != null) && !entry.declaredAsPrimary && entry.wiringValidated;

  }

  /**
   * The handler methods of the extensions. Both platform integrations offer this as a bean.
   * An extension registers its contracts and calls its methods through it.
   *
   * @return The registry, never <code>null</code>
   */
  public io.vanillabp.integration.adapter.migration.handler.ExtensionHandlerRegistry getExtensionHandlers() {

    return extensionHandlers;

  }

  /**
   * Writes the report about the handler methods of a workflow service class which the scan
   * cannot reach, once per class. It is a WARN and does not end the boot. Suppose the BPMN
   * model really has the task which such a method was meant to serve. Then the application
   * does not start anyway, because the wiring check asks for a method it does not find. This
   * sentence says why that method is missing, although the developer can read it in their
   * own source.
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
      findings
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.CODE,
              "workflow service '%s'".formatted(workflowServiceClass.getName()),
              report);
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
   * The workflow aggregate on which a BPMN process of a workflow module works, or
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
        // overlapping version ranges are ambiguous. Ranges which do not overlap are a valid
        // way to serve several process versions ('1-2' next to '>2' is fine, '1-3' next to
        // '2' is not)
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
      // the wiring check already reports a process which no @WorkflowService class serves.
      // There is no aggregate class to ask here
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

  @Override
  public void reportCompensation(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec> compensations) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((entry == null) || (entry.processService == null)) {
      // a process which no @WorkflowService class serves has no aggregate to warn about.
      // The same is true for the elements reported next to this
      return;
    }
    concurrentTokenCheck
        .reportCompensation(
            workflowModuleId,
            bpmnProcessId,
            entry.processService.getWorkflowAggregateClass(),
            entry.processService.detectsConcurrentModification(),
            compensations);

  }

  @Override
  public void reportModelExpressions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<io.vanillabp.integration.adapter.spi.expressions.ModelExpression> expressions) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      // a process which no @WorkflowService class serves is a model which this application
      // does not own. It travels to the BPMS because it shares a file with the process which
      // IS served. Asking for its expressions to be rewritten would ask for a file which
      // nobody here can change. The deployment reports such a process on its own
      return;
    }
    modelExpressionCheck.reportModelExpressions(workflowModuleId, bpmnProcessId, expressions);

  }

  /**
   * The same for the versions which a BPMS still holds with running workflows. The startup
   * check for old process versions reads them. The elements of an older model reach an
   * aggregate exactly like the elements of the deployed one. And those workflows are the
   * ones which run longest.
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

    validateTaskWiring(null, workflowModuleId, bpmnProcessId, tasks);

  }

  @Override
  public boolean isImplementedExternally(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnTaskSpec task) {

    return implementedExternally.isMarked(adapterId, workflowModuleId, bpmnProcessId, task);

  }

  @Override
  public void validateTaskWiring(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmnTaskSpec> tasks) {

    // what the modeller wrote on the elements. Nothing here decides anything by it. An
    // extension which asks for it would otherwise parse the same BPMN bytes again
    extensionHandlers.rememberBpmnTaskNames(workflowModuleId, bpmnProcessId, tasks);
    // the names which a line of 'implemented-externally' may use. They are checked against
    // the configuration once the module is deployed
    implementedExternally.rememberTasks(workflowModuleId, bpmnProcessId, tasks);

    final var key = new RegistryKey(workflowModuleId, bpmnProcessId);
    final var entry = entries.get(key);
    if (entry == null) {
      // nobody claimed this process, so there is nothing to check it against. An adapter
      // does not wire such a process at all. And the deployment has already ended the start
      // because of it, unless the application marked it as somebody else's
      return;
    }
    final var handlers = List.copyOf(entry.handlers);
    entry.wiringValidated = true;
    implementedExternally.rememberClaimed(workflowModuleId, bpmnProcessId);
    tasksOfDeployedModels.put(new DeployedModelKey(adapterId, workflowModuleId, bpmnProcessId), List.copyOf(tasks));

    // mark every matched handler as wired. The reverse direction (methods which match no
    // task of ANY process of the module) is checked per module, in
    // validateNoUnwiredWorkflowTaskMethods, after all processes were wired
    handlers
        .stream()
        .filter(handler -> tasks.stream().anyMatch(task -> matches(handler, task)))
        .forEach(WorkflowTaskHandler::markWired);

    // every task needs a method, or a line which says that something else serves it. That
    // includes a user task. Version 1 asked for the method. A task which nobody here serves
    // is either a forgotten method or somebody else's work, and only the application can
    // tell which. A line at the task itself next to a method would answer the task twice, so
    // that ends the boot as well. A line above the task covers only the tasks without a
    // method
    final var unmatchedTasks = new LinkedList<BpmnTaskSpec>();
    final var servedTwice = new LinkedList<BpmnTaskSpec>();
    for (final var task : tasks) {
      final var served = handlers.stream().anyMatch(handler -> matches(handler, task));
      final var marking = implementedExternally.markingOf(adapterId, workflowModuleId, bpmnProcessId, task);
      if (!served && (marking == ImplementedExternallyCheck.Marking.NONE)) {
        unmatchedTasks.add(task);
      } else if (served && (marking == ImplementedExternallyCheck.Marking.AT_THE_TASK)) {
        servedTwice.add(task);
      } else if (!served && (marking == ImplementedExternallyCheck.Marking.INHERITED)) {
        implementedExternally.rememberAnInheritedMarkingWasUsed(workflowModuleId, bpmnProcessId);
      }
    }
    if (unmatchedTasks.isEmpty() && servedTwice.isEmpty()) {
      return;
    }

    final var message = new StringBuilder(
        "Task wiring of BPMN process '%s' of workflow module '%s' is incomplete!"
            .formatted(bpmnProcessId, workflowModuleId));
    // a registered process always has the class which registered it. So the message can name
    // where the missing method belongs
    final var serviceClasses = entry.workflowServiceClasses
        .stream()
        .map(Class::getName)
        .collect(Collectors.joining("', '", "'", "'"));
    if (!unmatchedTasks.isEmpty()) {
      message.append("\nBPMN tasks having no matching @WorkflowTask method:");
      unmatchedTasks.forEach(task -> message.append(
          """

                - %s: add a method annotated with @WorkflowTask named '%s' to %s, or annotate an \
              existing method with %s.
              %s"""
              .formatted(
                  describe(task),
                  task.taskDefinition() != null
                      ? task.taskDefinition()
                      : task.activityId(),
                  serviceClasses,
                  task.listener()
                      ? "@WorkflowTask(taskDefinition = \"%s\")".formatted(task.taskDefinition())
                      : "@WorkflowTask(taskDefinition = \"%s\") or @WorkflowTask(id = \"%s\")"
                          .formatted(task.taskDefinition(), task.activityId()),
                  io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally
                      .howToMark(workflowModuleId, bpmnProcessId, task)
                      .indent(4)
                      .stripTrailing())));
    }
    if (!servedTwice.isEmpty()) {
      message.append(
          "\nBPMN tasks having a @WorkflowTask method AND a line at the task saying that something else "
              + "serves them ('implemented-externally=true'), so the method and the other worker would both "
              + "answer them:");
      servedTwice.forEach(task -> message.append(
          "%n  - %s: remove the method or the line, depending on who is meant to serve it."
              .formatted(describe(task))));
    }
    message.append("\nTasks of the BPMN process: ");
    message.append(tasks.isEmpty()
        ? "none"
        : tasks
            .stream()
            .map(WorkflowTaskRegistry::describe)
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

  /**
   * One task as a message names it: the element, its task definition, and whether it is a
   * listener on that element.
   *
   * @param task The task
   * @return The words
   */
  private static String describe(
      final BpmnTaskSpec task) {

    return task.listener()
        ? "listener on '%s' (task definition '%s')".formatted(task.activityId(), task.taskDefinition())
        : "task '%s' (task definition '%s')".formatted(task.activityId(), task.taskDefinition());

  }


  /**
   * Says once per workflow module which <code>&#64;WorkflowTask</code> methods would be
   * called with missing values where VanillaBP works out a cancellation itself.
   * <p>
   * A derived cancellation has no job of the element, because the element is gone by the
   * time anybody notices. So a <code>&#64;TaskParam</code> and a multi-instance value reach
   * such a method as <code>null</code>. A value which is silently missing is the kind of
   * thing an application finds out in production. Only the methods which really declare one
   * are named. And only those which asked for the event at all. A method without a
   * <code>&#64;TaskEvent</code> parameter never sees a cancellation, and has nothing to read
   * here.
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

    // the other direction of the same question about the configuration: a line which says
    // that something else serves a task which no deployed model has. It is asked here,
    // because this is the moment when every adapter of the module has handed over its models
    implementedExternally.warnAboutLinesNoModelNeeds(workflowModuleId);

    // a method may be registered under several BPMN processes (secondary processes, several
    // handler objects). It is unwired only if NO registration of it was matched by any wired
    // process
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
    // a method whose BPMN processes were ALL never wired (no BPMN deployed, for example
    // because it arrives later during a migration) is not a defect. Only methods of at least
    // one process which was really wired are reported
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
   * Whether the method exists only for OLDER versions of its process. Then it matches no
   * task of the model this boot deployed. That is the point of it, not a defect.
   * <p>
   * Without this, nobody could keep a method for a version which the BPMS still holds. The
   * reverse direction would demand that the deployed model still has the task which the
   * newer model dropped. So an application could only serve an old version by keeping a dead
   * task in its current BPMN.
   * <p>
   * A BPMN process id which the application declares WITHOUT bringing a model for it is the
   * same case with a different limit. An example is the id which a renamed process left
   * behind. This boot deployed no version of it at all. So its methods are kept for the
   * versions which the BPMS holds under it.
   */
  private boolean servesOnlyOlderVersions(
      final RegistryKey key,
      final WorkflowTaskHandler handler) {

    if (isDeclaredWithoutDeployment(key.workflowModuleId(), key.bpmnProcessId())) {
      // nothing was deployed under this id. So no model of this boot has the task to which
      // this method is wired. That is what declaring the id says: the method serves what the
      // BPMS still holds under it. A method which serves none of those versions is reported.
      // Where the BPMS holds nothing at all, the check for old versions says so about the id
      // itself, and not about every method kept for it
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
      // no BPMS counts versions. So there is nothing to exempt, and the reverse direction
      // stays as strict as before
      return false;
    }
    final var resolver = processVersions.resolverFor(key.workflowModuleId(), key.bpmnProcessId());
    // a method which serves the version deployed by ANY of the BPMS has to match a task of
    // that model. Only a method which is excluded everywhere is one for old versions
    return deployedVersions
        .stream()
        .noneMatch(version -> handler.matchesVersion(version, resolver));

  }

  /**
   * Whether this method serves the delivery at hand. It asks with BOTH keys by which a task
   * is wired. A method names one of them (<code>&#64;WorkflowTask(taskDefinition = ...)</code>
   * or <code>&#64;WorkflowTask(id = ...)</code>). A delivery reports both: the task
   * definition the BPMS subscribed to, and the id of the element it belongs to.
   * <p>
   * <code>validateTaskWiring</code> matches a method against the same pair while the
   * application boots, and that is the point. A wiring which the boot accepts has to be a
   * wiring which a delivery finds. Asking with the task definition alone accepted a method
   * wired by the element id at deployment, and failed it at the first delivery. The
   * application learnt about it from an incident.
   * <p>
   * The element id is also compared with the reported task definition. That is for an
   * adapter which names no element of its own. There the one value it reports IS the element
   * id.
   * <p>
   * Decision 67 in the repository's DECISIONS.md says why the routing learns the second key,
   * instead of the deployment refusing such a wiring.
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
   * How a delivery names its BPMN element in the message about a method which nobody found.
   * It is empty where the adapter names no element. On most models the two keys are
   * different values. A reader who only sees the task definition looks for a method under a
   * name which their model does not have.
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

    // only a method which names the task definition serves a listener. The element id names
    // the element, and that may have a method of its own
    return sameWiring(handler.getTaskDefinition(), task.taskDefinition()) || (!task.listener() && sameWiring(handler
        .getActivityId(), task.activityId()));

  }

  @Override
  public WorkflowTaskOutcome invokeWorkflowTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext context) {

    // asked before anything else is done with this delivery. A workflow which names itself
    // twice is not served, whichever method would have served it
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
                    // otherwise a delivery from a version which the configuration faded
                    // out looks exactly like a wiring defect
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
   * Reports the tasks which the BPMS does not have any more. An adapter asks this after it
   * handed a delivery over, see
   * {@link WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas(String, String, TaskInvocationContext, OpenTaskProbe)}.
   * <p>
   * It skips these cases, in this order, because each step is cheaper than the next. First,
   * a BPMN process with no asynchronous task at all. Most applications are like that, and
   * they then pay nothing. Then a scope whose configuration switched the probing off. Then a
   * delivery which names no workflow. And last an application without a delivery log.
   * <p>
   * Nothing here may cost the caller its own work. The delivery is done by the time an
   * adapter asks. A probe which throws, or a derived cancellation which fails, must not turn
   * a finished job into a failed one. So both stay inside this call.
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
    // a BPMN process whose methods all complete when they return leaves no task open. So
    // there is nothing this could ever find, and that is the case for most applications
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
        // the task being worked on right now is not a candidate. It is open because this
        // very delivery left it open
        .filter(record -> !java.util.Objects.equals(record.taskId(), wakeUp.getTaskId()))
        // and a record which another BPMS wrote belongs to that BPMS. This probe knows nothing
        // about its tasks
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
   * cancellation. A probe which cannot say is not a probe which said no. That alone keeps
   * this safe for a BPMS whose API cannot tell a refusal from an outage (see decision 74 in
   * the repository's DECISIONS.md).
   * <p>
   * A probe which throws counts as "cannot say", and it is reported once. The delivery which
   * led here is done. An adapter whose BPMS has a hiccup must not lose it over a question
   * which nobody asked for.
   * <p>
   * The record's task definition travels with the question. The two ids do not say which
   * kind of task is asked about, and some BPMS answer only for some kinds. A probe which
   * implements only the method with two arguments gets it through the default, and decides
   * exactly as before.
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
          // a BPMS which said it keeps no list of versions has answered every version
          // question of this process. So the methods which wait for an answer are named here,
          // and the messages about an unknown version stay out of it
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
          // the BPMS is asked ONCE per process, right after the deployment. The version
          // which this boot deployed is part of the answer. A tag which the application names
          // is either known now, or reported as unknown
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
            // ranges which name a tag could not be placed at registration. Now they can
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

    // a method which never runs is a statement about the whole workflow module. So it is
    // made once every BPMN process of the module was asked about its versions
    deployedVersionsCheck.reportDeadHandlers(workflowModuleId);

    bpmsInitiatedStarts.resolveProcessVersions(workflowModuleId);
    workflowEndedHandlers.resolveProcessVersions(workflowModuleId);
    extensionHandlers.resolveProcessVersions(workflowModuleId);

    // last, because it compares version ranges. No adapter wires an id under which nothing
    // was deployed. So the start events which its methods serve are judged here or
    // nowhere
    bpmsInitiatedStarts.validateAgainstVersionsTheBpmsHolds(workflowModuleId);

  }

  @Override
  public void validateBpmsInitiatedStarts(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmsInitiatedStartSpec> startEvents) {

    if (isACalledProcess(workflowModuleId, bpmnProcessId)) {
      refuseStartEventsTheBpmsFiresInACalledProcess(workflowModuleId, bpmnProcessId, startEvents);
      return;
    }
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    bpmsInitiatedStarts
        .validate(
            workflowModuleId,
            bpmnProcessId,
            startEvents,
            (entry != null) && (entry.processService != null)
                ? entry.processService.getWorkflowAggregateClass()
                : null);

  }

  /**
   * Ends the deployment of a called process which has a start event that the BPMS fires by
   * itself. The start of a called process starts no workflow. So nothing would build the
   * aggregate of the instance which such a start event creates. The
   * <code>&#64;WorkflowStartedByBpms</code> methods of the class are not judged against a
   * called process, because they serve the process which starts the workflow.
   */
  private static void refuseStartEventsTheBpmsFiresInACalledProcess(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmsInitiatedStartSpec> startEvents) {

    final var firedByTheBpms = startEvents
        .stream()
        .filter(spec -> (spec.kind() == io.vanillabp.spi.service.BpmsStartTrigger.Kind.TIMER) || (spec
            .kind() == io.vanillabp.spi.service.BpmsStartTrigger.Kind.SIGNAL) || (spec
                .kind() == io.vanillabp.spi.service.BpmsStartTrigger.Kind.CONDITIONAL))
        .map(BpmsInitiatedStartSpec::elementId)
        .toList();
    if (firedByTheBpms.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            BPMN process '%s' of workflow module '%s' is declared in 'secondaryBpmnProcesses', so it \
            is a called process and starts no workflow of its own. But it has start event(s) the \
            BPMS fires on its own: %s. Nothing would build the workflow aggregate of an instance \
            such a start event creates. Either declare the process as the 'bpmnProcess' of a \
            @WorkflowService class, or remove those start events from the model."""
            .formatted(bpmnProcessId, workflowModuleId, firedByTheBpms));

  }

  @Override
  public void reportStartMessages(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> messageNames) {

    startMessages.report(adapterId, workflowModuleId, bpmnProcessId, messageNames);

  }

  @Override
  public boolean startsAWorkflowOfItsOwn(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return !isACalledProcess(workflowModuleId, bpmnProcessId);

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

    if (isACalledProcess(workflowModuleId, bpmnProcessId)) {
      // an adapter which asks startsAWorkflowOfItsOwn never gets here. A model deployed
      // before the question existed still has the listener. Its start is answered with the
      // caller's aggregate, without a start row and without building anything
      return io.vanillabp.integration.adapter.migration.workflowstart.BpmsInitiatedStartExecution
          .theStartOfACalledProcess(entry.processService, context);
    }

    final var result = bpmsInitiatedStarts
        .start(entry.processService, context, entry.processService.getTransactionRunner(transactionRunner));
    if ((aggregateSync == null) || (context
        .getAggregateSyncMode() == io.vanillabp.integration.adapter.spi.AggregateSyncMode.NONE)) {
      return result;
    }

    // the aggregate values shared with a remote BPMS. They are read AFTER the transaction of
    // the start committed. That is why an embedded BPMS (which joins the caller's
    // transaction, sync mode NONE) never gets here
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
   * Whether the end of a workflow of that BPMN process has to be reported at all. The
   * adapters ask this while they wire. So a model pays for a listener or a worker only where
   * the end is used.
   * <p>
   * Besides an application's <code>&#64;WorkflowEnded</code> method, there are two more
   * reasons to want it. A workflow module which releases the records of its processed task
   * deliveries when a workflow ends needs the notification for that. So does an election
   * cache which should let go of the hint of an ended workflow early. That is why no adapter
   * had to change for either of them.
   */
  @Override
  public boolean workflowEndedHandlerExists(
      final String workflowModuleId,
      final String bpmnProcessId) {

    // the end of a called process is a step of the workflow which called it
    if (isACalledProcess(workflowModuleId, bpmnProcessId)) {
      return false;
    }
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

    // the tasks first, each in a transaction of its own, then the end. A workflow which
    // ended has nothing open any more. So everything which the application still believes
    // is open in it is gone, and is reported as canceled
    reportTheTasksTheEndedWorkflowTookAway(workflowModuleId, entry, context);

    if (isACalledProcess(workflowModuleId, bpmnProcessId)) {
      // the tasks of the called instance are gone, and that is all there is to say. The
      // workflow goes on in the process which called it. So no method is called, the
      // election hint stays, and the records are released when the top workflow ends
      log
          .debug(
              "The called process '{}' of workflow module '{}' ended for workflow '{}' - that is a step "
                  + "of the workflow which called it, so the application is not told",
              bpmnProcessId,
              workflowModuleId,
              context.getWorkflowAggregateId());
      return;
    }

    workflowEndedHandlers
        .workflowEnded(entry.processService, context, entry.processService.getTransactionRunner(transactionRunner));

  }

  /**
   * Reports every task which the core still believes is open in the ended workflow as
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED}, before the end itself is
   * reported.
   * <p>
   * This only happens where the adapter named the workflow
   * ({@link WorkflowEndedContext#getWorkflowId()}). An adapter which names none keeps
   * behaving exactly as before. An adapter whose BPMS cancels each element by itself wants
   * that. Otherwise Camunda 7 would report the same task twice.
   * <p>
   * A record of ANOTHER adapter is left alone. {@link TaskDelivery#adapterId()} says who
   * delivered the task, so nothing has to be asked. During a migration one aggregate may
   * have work of two BPMS. The end which this adapter reports says nothing about the other
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
    // the UNION over every method which serves this element. Methods wired to one BPMN
    // element differ in the process version they serve, and the delivery has to satisfy
    // whichever of them runs. Sorted, because a subscription which names variables is
    // compared with itself across restarts
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
  public Collection<String> extensionTaskParameterNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    return extensionHandlers.taskParameterNamesOfEveryContract(workflowModuleId, bpmnProcessId, lookupKeys);

  }

  /**
   * Which elements of one task of one held version are iterated without naming the value of
   * a round, although a method which serves that version reads it.
   * <p>
   * The two halves meet here for the same reason as in
   * {@link #tasksNotServedInVersion(String, String, String, Collection)}. Only the adapter
   * can read the model which a BPMS still holds. Only this registry knows what a method asks
   * for and which versions it serves.
   * <p>
   * An element whose item the method reads, but which is NOT in the given list, is no
   * finding. The multi-instance chain crosses a call activity. So a task of a called process
   * asks for an element of its caller, and the model at hand is the wrong place to look for
   * it. The question at deployment time follows the same rule.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version identifier the BPMS reported
   * @param task The task, as the model that version holds describes it
   * @return The element ids whose item a method which serves that version reads, and which
   *         that version's model never names
   */
  private Collection<String> itemsAHeldVersionNeverNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnTaskSpec task) {

    final var withoutAnItem = task.multiInstanceElementsWithoutAnItem();
    if ((withoutAnItem == null) || withoutAnItem.isEmpty()) {
      return List.of();
    }
    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      return List.of();
    }
    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    return entry.handlers
        .stream()
        .filter(handler -> matches(handler, task))
        .filter(handler -> handler.matchesVersion(version, resolver))
        .map(WorkflowTaskHandler::getMultiInstanceElementNames)
        .flatMap(List::stream)
        .filter(withoutAnItem::contains)
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
    // the UNION over every method which serves this element, for the same reason as in
    // taskParameterNames. Methods wired to one BPMN element differ in the process version
    // they serve, and all of them read their item from the model which the adapter asks
    // about
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
   * The sentence which a delivery from an OUTFADED version deserves. Without it the developer
   * reads "no method matches" and looks for a wiring defect. But the configuration says on
   * purpose that this version is not served any more.
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
   * The methods registered for that BPMN process which serve NO version worth serving: the
   * versions which the BPMS holds, minus the ones the configuration faded out. All four
   * kinds of handler have a <code>version</code> attribute, so all four are asked.
   * <p>
   * The verdict is reached for the WHOLE workflow module, not for the given process alone. A
   * class declares one <code>bpmnProcess</code> and any number of
   * <code>secondaryBpmnProcesses</code>. So each of its methods is registered several times,
   * once for each declared BPMN process, with the version range of that declaration. A method
   * which serves the versions of ANY of them runs. That is why the versions worth serving
   * arrive here per BPMN process of the module. This is for a renamed BPMN process. The
   * methods kept for the old id serve the versions which the BPMS holds under it, and none of
   * the new id. Calling them dead would be the opposite of what the declaration says.
   * <p>
   * Such a method never runs. It is a warning and not a boot failure, because a version
   * which does not exist YET is a normal state. An application may be rolled out before the
   * model which needs it. And during a rolling deployment another node may still be
   * deploying it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID the methods are reported for
   * @param servableVersionsByProcess The versions worth serving, per BPMN process of that
   *          workflow module
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
   * The methods registered for that BPMN process which a delivery of a BPMS without a list of
   * versions can never reach. This is the version half of
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}.
   * <p>
   * It covers the same four kinds of handler as {@link #handlersNotServingAnyVersion}, with
   * the same walk, but asks each method a different question. Not "which of the versions the
   * BPMS holds does it serve", because there are none. But "can a delivery of this BPMS meet
   * its specification at all".
   * <p>
   * Unlike the verdict about dead methods, this one is per BPMN process and not per workflow
   * module. What is reported here is true of THIS BPMS, and the message says so. So a method
   * which is the right one on another BPMS of the application is named, but not called
   * dead.
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
   * A walk over the methods of ONE kind of handler registered for one BPMN process. It
   * answers the given question about each of them.
   */
  @FunctionalInterface
  private interface HandlerWalk {

    List<HandlerVersions> of(
        String bpmnProcessId,
        java.util.function.Predicate<ServedVersions> serves);

  }

  /**
   * The four kinds of handler with a <code>version</code> attribute, each as a walk over the
   * methods of one BPMN process of the given workflow module.
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
   * Which methods of one kind of handler are dead in the given BPMN process. Dead means that
   * they serve no version worth serving there, AND none in any other BPMN process of the
   * workflow module they are registered for.
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
   * Whether a method serves one of the versions worth serving in a BPMN process. This is the
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
   * The <code>&#64;WorkflowTask</code> methods registered for one BPMN process, each with the
   * verdict which the given question reaches about its version specifications.
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
                // an adapter can build its own identifier from the task definition. A method
                // wired to a BPMN element id is matched through the model. The model of that id
                // is the one thing the application does not have. So it adds nothing. That is
                // why the SPI says that an id may be named with nothing served
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
   * methods for a BPMN process under which nothing was deployed during this boot. That is
   * the id which a renamed BPMN process left behind.
   * <p>
   * It returns fewer ids than the module declares, and both callers want exactly those. A
   * class which serves a BPMN process that this boot deployed is a class in use. A further
   * id which IT declares is the renamed process this is about. A class of which NONE of the
   * processes were deployed says nothing about a rename. It is a workflow service which waits
   * for a model that has not arrived yet. Treating every id of it as renamed would bury the
   * one case worth speaking about.
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
   * Which of the given tasks of ONE deployed version no <code>&#64;WorkflowTask</code> method
   * serves. It is the version-aware sibling of {@link #validateTaskWiring}. The startup check
   * for old process versions uses it.
   * <p>
   * It differs from the wiring check in two ways, and both matter. It asks per VERSION. So a
   * method with <code>version = "3"</code> counts for version 3 and for nothing else. And it
   * marks NOTHING as wired. Serving an old version says nothing about the reverse direction
   * which {@link #validateNoUnwiredWorkflowTaskMethods} decides. A method kept only for an old
   * version must still match a task of the deployed model.
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
        // the model which a deployment brings must have a method for every user task. An older
        // version which the BPMS only still holds does not need one. Nobody can change that
        // model any more, and otherwise the rule would mark a removed user task forever
        .filter(task -> !task.optional())
        // and a task which the application says somebody else serves is served
        .filter(task -> !implementedExternally.isMarked(null, workflowModuleId, bpmnProcessId, task))
        .filter(task -> handlers
            .stream()
            .noneMatch(handler -> matches(handler, task) && handler.matchesVersion(version, resolver)))
        .toList();

  }

  /**
   * Which tasks of the model this start deployed no <code>&#64;WorkflowTask</code> method
   * serves, in the version the BPMS gave that model. The wiring check could not ask this. It
   * runs before the deployment, when the version is not known yet. A delivery picks the
   * method by version, so a task left out here fails at every delivery. See decision 126 in
   * the repository's DECISIONS.md.
   * <p>
   * The tasks are the ones which the adapter handed over while it wired, so no model is read
   * again. The wiring check has accepted them. So a task without any method is one which the
   * application says somebody else serves. A task with methods needs one whose version range
   * covers the deployed version. That includes a user task.
   *
   * @param adapterId The adapter which deployed the model
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version the BPMS gave the model this start deployed
   * @return Every task which no method serves in that version, each with the methods wired
   *         to it and their version ranges. Empty where every task is served
   */
  public Map<BpmnTaskSpec, List<String>> tasksTheDeployedVersionLeavesUnserved(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (entry == null) {
      // nobody claimed the process, so no method is expected for it
      return Map.of();
    }
    var tasks = tasksOfDeployedModels.get(new DeployedModelKey(adapterId, workflowModuleId, bpmnProcessId));
    if (tasks == null) {
      // an adapter which wired the model without naming itself
      tasks = tasksOfDeployedModels.get(new DeployedModelKey(null, workflowModuleId, bpmnProcessId));
    }
    if (tasks == null) {
      return Map.of();
    }
    final var handlers = List.copyOf(entry.handlers);
    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    final var unserved = new java.util.LinkedHashMap<BpmnTaskSpec, List<String>>();
    for (final var task : tasks) {
      final var wired = handlers
          .stream()
          .filter(handler -> matches(handler, task))
          .toList();
      if (wired.isEmpty()) {
        // the wiring check let a task without a method pass only because a line of
        // 'implemented-externally' says that something else serves it
        continue;
      }
      if (wired.stream().anyMatch(handler -> handler.matchesVersion(version, resolver))) {
        continue;
      }
      unserved
          .put(
              task,
              wired
                  .stream()
                  .map(handler -> "method '%s' (version %s)"
                      .formatted(handler.describe(), handler.describeVersionsWithOrigin()))
                  .distinct()
                  .toList());
    }
    return unserved;

  }

  /**
   * Which elements of the model this start deployed no method serves, in the version the
   * BPMS gave that model. First the tasks of {@link #tasksTheDeployedVersionLeavesUnserved}.
   * Then the elements which the extensions reported for a contract that asks for it (see
   * decision 127 in the repository's DECISIONS.md).
   * <p>
   * The tasks are kept per adapter, the elements of an extension per BPMN process. Each
   * adapter checks both against the version which its own BPMS gave the model.
   *
   * @param adapterId The adapter which deployed the model
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version the BPMS gave the model this start deployed
   * @return Every element which no method serves in that version. Empty where every one is
   *         served
   */
  public List<DeployedProcessVersionsCheck.UnservedElement> elementsTheDeployedVersionLeavesUnserved(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    final var unserved = new java.util.LinkedList<DeployedProcessVersionsCheck.UnservedElement>();
    tasksTheDeployedVersionLeavesUnserved(adapterId, workflowModuleId, bpmnProcessId, version)
        .forEach((
            task,
            methods) -> unserved
                .add(new DeployedProcessVersionsCheck.UnservedElement(
                    "task '%s' (task definition '%s')".formatted(task.activityId(), task
                        .taskDefinition()), methods, DeployedProcessVersionsCheck.A_TASK_WITHOUT_A_METHOD_STOPS_EVERY_WORKFLOW)));
    unserved
        .addAll(extensionHandlers
            .elementsTheDeployedVersionLeavesUnserved(
                workflowModuleId,
                bpmnProcessId,
                version,
                processVersions.resolverFor(workflowModuleId, bpmnProcessId)));
    return unserved;

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
    // ONE method which wants to keep the task open is enough (see the SPI). Methods which
    // serve different process versions share the BPMN element, and the element either can
    // stay open or it cannot
    return entry.handlers
        .stream()
        .filter(candidate -> sameWiring(candidate.getTaskDefinition(),
            taskDefinitionOrActivityId) || sameWiring(candidate.getActivityId(), taskDefinitionOrActivityId))
        .anyMatch(WorkflowTaskHandler::isAsynchronousTask);

  }

  /**
   * Answered from the registry itself, and not through
   * {@link #resolveWorkflowAggregateIdName(String, String)}. The name of the aggregate's ID
   * property is asked of the persistence. An application on a BPMS with a business key may
   * leave it unanswered. That says nothing about whether a workflow service claims the
   * process. See decision 120 in the repository's DECISIONS.md.
   */
  @Override
  public boolean isClaimedByAWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var entry = entries.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    return (entry != null) && (entry.processService != null);

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
   * The shared values of an aggregate. They are read after the caller's transaction
   * committed (a remote BPMS which completes a task), or within it (an embedded engine which
   * completes the task in the same transaction).
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
    // for a remote BPMS the caller's transaction (the one in which the @WorkflowTask ran) is
    // COMMITTED at this point. So the aggregate is loaded in a new one. An embedded engine
    // is still inside that transaction and reads there. A failure must never prevent the
    // task from being completed, because the BPMS would redeliver it forever.
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

    // an embedded engine completes the task in the transaction in which the handler ran. So
    // the values have to be read from the aggregate as it is NOW. A new transaction would
    // see the state before the handler, or wait for the row which this one holds
    return syncedValues(
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId,
        adapterDefault,
        true);

  }

  // the suppression does not doubt the deprecation. It keeps it quiet. 'forRemoval' raises
  // the mandatory 'removal' lint at every implementation, and @Deprecated on the override
  // does not silence it. It goes when the fallback goes.
  @Deprecated(forRemoval = true)
  @SuppressWarnings("removal")
  @Override
  public boolean workflowAggregateHasProperty(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String propertyName) {

    // the migration fallback. Version 1 also resolved attributes without a getter, so the
    // reader looks at getters AND fields
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
    // runs within the CALLER's transaction. An embedded BPMS evaluates expressions inside
    // an engine transaction, and the aggregate has to join it
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
          // the first segment may be a variable of the model and not an attribute of the
          // aggregate. The question about a single name draws the same line
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
