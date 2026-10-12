package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The bean an application injects as {@link io.vanillabp.spi.process.ProcessService}, one per
 * workflow aggregate class.
 * <p>
 * The class is abstract, because what differs per aggregate is settled while the application
 * is built. {@code ProcessServiceBuildStepProcessor} of the deployment module generates a
 * subclass per aggregate. The subclass answers the abstract methods below with constants. The
 * build step registers it as an {@code @ApplicationScoped} bean. What a call then does lives
 * in the core: in {@link ProcessServiceBase} and in the {@link MigrationProcessService} built
 * by {@link #initialize()}. This class is the CDI half of it. The beans of the application
 * arrive through the injected fields and are handed over. So nothing in the core has to know
 * a platform.
 *
 * @param <A> The workflow aggregate class this service serves
 */
@Slf4j
public abstract class ProcessServiceBaseCdiBean<A> extends ProcessServiceBase<A> {

  /**
   * The generated subclass calls it, and the CDI container creates that subclass. An
   * application neither creates nor extends this class. The fields below are injected
   * afterwards. Only then does {@link #initialize()} have what it needs.
   */
  public ProcessServiceBaseCdiBean() {
  }

  @Inject
  MigrationAdapterProperties properties;

  /**
   * The persistences which are there at runtime. They are injected as {@link Instance},
   * because the services annotated with {@link io.vanillabp.spi.service.WorkflowService} may
   * implement {@link AggregatePersistenceAware}. This bean is usually injected into the
   * service as well, so a direct injection would be a cycle.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> persistences;

  /**
   * The process services of the VanillaBP adapters which are there at runtime. The
   * convention is one <i>element</i> bean per adapter, never a bean of type
   * <code>List&lt;MigratableProcessService&gt;</code>. So several adapter types can live
   * side by side in one application.
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.adapter.spi.MigratableProcessService<?>> migratableProcessServices;

  /**
   * A second accepted shape: beans of type
   * <code>List&lt;MigratableProcessService&lt;Object&gt;&gt;</code>. They are flattened into
   * the collected process services. Some synthetic beans are created from runtime
   * configuration, one process service per configured adapter id. On Quarkus they cannot
   * be registered as single element beans. A single List bean per adapter is the documented
   * shape there. By convention, the type parameter of the element is LITERALLY
   * {@code Object}. CDI does not match parameterized types with nested wildcards reliably in
   * all modes. So the platform looks the beans up with the exact type.
   */
  @Inject
  @Any
  Instance<List<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>>> migratableProcessServiceLists;

  /**
   * Which outbox an aggregate's transaction reaches (mixed persistence, outboxes of its own).
   * It is the bean of {@link PhaseTwoOutboxResolverProducer}, not a resolver of this process
   * service's own. An extension which writes entries of its own has to get the same answer.
   * Two resolvers built in two places would give two answers as soon as one of them
   * changes.
   */
  @Inject
  PhaseTwoOutboxResolver phaseTwoOutboxResolver;

  /**
   * Which log of processed task deliveries an aggregate's transaction reaches (mixed
   * persistence, stores of its own). It is the bean of {@link TaskDeliveryLogResolverProducer},
   * not a resolver of this process service's own. An extension which reads what a workflow is
   * waiting for has to get the same answer. Two resolvers built in two places would give two
   * answers as soon as one of them changes.
   */
  @Inject
  TaskDeliveryLogResolver taskDeliveryLogResolver;

  /**
   * Answers whether a transaction is open where no runner of an aggregate can be asked. See
   * {@link #noTransactionIsActive()}.
   */
  @Inject
  TransactionSynchronizationRegistry txRegistry;

  /**
   * Through which transaction a workflow aggregate is written. It is the application's runner
   * where the application added one, and the platform's JTA otherwise. It is the bean of
   * {@link TransactionRunnerProducer}, not a resolver of this process service's own. An
   * extension which writes into the transaction of the same aggregate asks the same bean.
   * Two resolvers built in two places would give two answers as soon as one of them
   * changes.
   */
  @Inject
  TransactionRunnerResolver transactionRunnerResolver;

  /**
   * The router, owned by the core, which dispatches committed phase-two outbox entries. This
   * bean registers its process service with it when the bean is created. The process
   * service also converts the aggregate ID.
   */
  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  /**
   * The registry of <code>&#64;WorkflowTask</code> handlers, owned by the core. It is
   * resolved through {@link Instance} to avoid cycles, like the other collaborators.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry> workflowTaskRegistry;

  /**
   * The cache which remembers which adapter holds which workflow. The BPMS election asks it.
   * It is the platform's default in memory, or the application's own bean, e.g. one shared
   * in a cluster.
   */
  @Inject
  Instance<io.vanillabp.integration.spi.WorkflowAdapterCache> workflowAdapterCache;

  /**
   * The numbers of the election cache for the whole application. Every lookup of whatever
   * cache is in use is counted into this one instance.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics> workflowAdapterCacheStatistics;

  /**
   * Where deliveries of this process are counted. It is unsatisfied where the application
   * uses no Micrometer extension.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> vanillaBpMetrics;

  @Getter
  MigrationProcessService<A> migrationProcessService;

  /**
   * The process services of every declared BPMN process id, the primary one first - see
   * {@link #getProcessServicesOfDeclaredIds()}.
   */
  private List<MigrationProcessService<A>> processServicesOfDeclaredIds;

  /**
   * The persistence bean through which this service loads and saves its aggregates. It is
   * named by the class it was DECLARED as. It is either the {@link AggregatePersistenceAware}
   * implementation of the application, or the one which VanillaBP generated for the way the
   * aggregate is persisted. The choice is made while the application is built. So a second
   * implementation added later changes what is deployed, not what a running application
   * picks.
   *
   * @return The class the persistence is looked up by, see {@code getAggregatePersistence()}
   */
  public abstract Class<AggregatePersistenceAware<A>> getAggregatePersistenceClass();

  /**
   * The workflow aggregate which this service serves. The store resolvers and the core's
   * reflection on the aggregate's id start from it. The generated subclass answers with a
   * constant of its own. It does not leave the class to be read from a type parameter.
   *
   * @return The workflow aggregate class
   */
  public abstract Class<A> getWorkflowAggregateClass();

  /**
   * The PRIMARY BPMN process id of this service. It is what
   * <code>&#64;WorkflowService.bpmnProcess</code> names, or the simple name of the workflow
   * service class where it names nothing. Every call of the application reaches this process.
   * A task of a secondary process is delivered to the service built for that id (see
   * {@link #getProcessServicesOfDeclaredIds()}).
   *
   * @return The BPMN process id which the calls of the application address
   */
  public abstract String getBpmnProcessId();

  /**
   * All combinations of workflow module, workflow service class and BPMN process ID for the
   * aggregate which this process service serves. They are found at build time. Every class
   * which declares the aggregate adds all its declared BPMN process IDs
   * ({@code @WorkflowService.bpmnProcess} and {@code secondaryBpmnProcesses}). Encoding:
   * entries {@code <module>|<class name>|<bpmn process id>}, joined by {@code ';'}. It is
   * generated as a constant in the class file, which is easy to do with Gizmo.
   *
   * @return The encoded registrations, never empty. An aggregate without a workflow service
   *         class gets no process service either
   */
  public abstract String getWorkflowTaskRegistrations();

  /**
   * Builds everything which this bean serves with, once, after the container created it: the
   * core's {@link MigrationProcessService} of the primary BPMN process, the registration for
   * the phase-two routing, and the workflow service classes of every declared id.
   * <p>
   * The beans which it collects are injected into this instance first. That is why the work
   * is done here and not in the constructor.
   */
  @PostConstruct
  public void initialize() {

    // Collect the element beans plus the flattened List beans (see the field's Javadoc).
    @SuppressWarnings("unchecked")
    final List<io.vanillabp.integration.adapter.spi.MigratableProcessService<A>> processServices = java.util.stream.Stream
        .concat(
            migratableProcessServices.stream(),
            migratableProcessServiceLists
                .stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream))
        .filter(java.util.Objects::nonNull)
        .map(processService -> (io.vanillabp.integration.adapter.spi.MigratableProcessService<A>) processService)
        .toList();

    final var electionCache = io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache
        .instrument(
            workflowAdapterCache.isResolvable()
                ? workflowAdapterCache.get()
                : null,
            workflowAdapterCacheStatistics.isResolvable()
                ? workflowAdapterCacheStatistics.get()
                : null);
    this.migrationProcessService = MigrationProcessService
        .forBpmnProcess(getWorkflowModuleId(), getBpmnProcessId(), getWorkflowAggregateClass())
        .properties(properties)
        .aggregatePersistence(getAggregatePersistence())
        .processServices(processServices)
        .phaseTwoOutboxResolver(phaseTwoOutboxResolver)
        .workflowAdapterCache(electionCache)
        .taskDeliveryLogResolver(taskDeliveryLogResolver)
        .transactionRunnerResolver(transactionRunnerResolver)
        .build();
    this.migrationProcessService
        .setMetrics(PhaseTwoRouterProducer.vanillaBpMetricsOf(vanillaBpMetrics));

    // Register as a target of the phase-two dispatch. Outbox entries for this workflow
    // module and BPMN process are routed here after the local transaction committed.
    if (phaseTwoRouter.isResolvable()) {
      phaseTwoRouter
          .get()
          .register(migrationProcessService);
    }

    registerWorkflowTaskHandlers(
        processServices, phaseTwoOutboxResolver, electionCache, taskDeliveryLogResolver, transactionRunnerResolver);

  }

  /**
   * Registers all workflow service classes of this aggregate with the core's workflow-task
   * registry, under all BPMN process IDs they declare. These are facts from the build, see
   * {@link #getWorkflowTaskRegistrations()}. Secondary BPMN processes get their own
   * {@link MigrationProcessService}, which is also registered for the phase-two routing. The
   * primary one is used again.
   */
  private void registerWorkflowTaskHandlers(
      final List<io.vanillabp.integration.adapter.spi.MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final io.vanillabp.integration.spi.WorkflowAdapterCache electionCache,
      final TaskDeliveryLogResolver taskDeliveryLogResolver,
      final TransactionRunnerResolver transactionRunnerResolver) {

    if (!workflowTaskRegistry.isResolvable()) {
      return;
    }
    final var registry = workflowTaskRegistry.get();
    // Keeps the order of insertion, and the primary service goes in first. The startup
    // checks run over this list, and a reader expects the message about the primary id
    // first.
    final var processServicesByKey = new java.util.LinkedHashMap<String, MigrationProcessService<A>>();
    processServicesByKey.put(
        "%s|%s".formatted(getWorkflowModuleId(), getBpmnProcessId()),
        migrationProcessService);
    // An awareness probe is asked about a workflow module AND about every BPMN process
    // which serves this aggregate there. This includes the secondary processes of the
    // same @WorkflowService, because they run on the same workflow.
    final var processIdsByModule = new java.util.LinkedHashMap<String, java.util.List<String>>();
    final var moduleOfProcessService = new java.util.LinkedHashMap<MigrationProcessService<A>, String>();
    moduleOfProcessService.put(migrationProcessService, getWorkflowModuleId());
    processIdsByModule
        .computeIfAbsent(getWorkflowModuleId(), module -> new java.util.LinkedList<>())
        .add(getBpmnProcessId());
    for (final var registration : getWorkflowTaskRegistrations().split(";")) {
      final var parts = registration.split("\\|");
      final var moduleId = parts[0];
      final var serviceClassName = parts[1];
      final var bpmnProcessId = parts[2];
      final Class<?> serviceClass;
      try {
        serviceClass = Class.forName(
            serviceClassName,
            false,
            Thread.currentThread().getContextClassLoader());
      } catch (final ClassNotFoundException e) {
        throw new IllegalStateException(
            "Workflow service class '%s' recorded at build time was not found at runtime!"
                .formatted(serviceClassName), e);
      }
      final var processService = processServicesByKey.computeIfAbsent(
          "%s|%s".formatted(moduleId, bpmnProcessId),
          key -> {
            final var secondaryProcessService = MigrationProcessService
                .forBpmnProcess(moduleId, bpmnProcessId, getWorkflowAggregateClass())
                .properties(properties)
                .aggregatePersistence(getAggregatePersistence())
                .processServices(processServices)
                .phaseTwoOutboxResolver(phaseTwoOutboxResolver)
                .workflowAdapterCache(electionCache)
                .taskDeliveryLogResolver(taskDeliveryLogResolver)
                .transactionRunnerResolver(transactionRunnerResolver)
                .build();
            secondaryProcessService
                .setMetrics(PhaseTwoRouterProducer.vanillaBpMetricsOf(vanillaBpMetrics));
            if (phaseTwoRouter.isResolvable()) {
              phaseTwoRouter
                  .get()
                  .register(secondaryProcessService);
            }
            return secondaryProcessService;
          });
      moduleOfProcessService.put(processService, moduleId);
      final var declaredIds = processIdsByModule
          .computeIfAbsent(moduleId, module -> new java.util.LinkedList<>());
      if (!declaredIds.contains(bpmnProcessId)) {
        declaredIds.add(bpmnProcessId);
      }
      registry.registerWorkflowService(
          moduleId,
          bpmnProcessId,
          serviceClass,
          () -> jakarta.enterprise.inject.spi.CDI
              .current()
              .select(serviceClass)
              .get(),
          type -> {
            final var candidates = jakarta.enterprise.inject.spi.CDI
                .current()
                .select(type);
            return candidates.isResolvable()
                ? candidates.get()
                : null;
          },
          processService);
    }

    // Everything which can be configured per workflow can also be configured for a
    // secondary id or an id which is only declared. So the startup checks get every
    // declared id, not only the primary one.
    processServicesOfDeclaredIds = List.copyOf(processServicesByKey.values());

    // Every process service of this aggregate answers for the processes of ITS workflow
    // module.
    moduleOfProcessService
        .forEach((
            processService,
            moduleId) -> processService.setServedBpmnProcessIds(processIdsByModule.get(moduleId)));

  }

  /**
   * The startup checks. The generated process-service beans inherit observer methods. If the
   * first-priority adapter of this process needs a two-phase commit, the phase-two outbox is
   * resolved AT STARTUP. So a missing outbox fails the boot with a guiding message. It does
   * not show up only at the first workflow start. The log of processed task deliveries is
   * resolved in the same pass. A BPMS which repeats deliveries without a log to remember
   * them is reported at startup, not at the first repeated delivery.
   *
   * @param event The startup event observed
   */
  public void onStart(
      @Observes final StartupEvent event) {

    // Once per DECLARED BPMN process id, not once per bean. A secondary id, or an id which
    // is only declared, has its own prioritized adapters, outbox and persisted leftovers.
    // And we ask in order to get a message which names the id it is about.
    getProcessServicesOfDeclaredIds()
        .forEach(processService -> {
          // First of all: an adapter which cannot serve an operation that every adapter has
          // to serve. Nothing later would report this gap, only a workflow which stands
          // still.
          processService.validateAdapterOperationsAtStartup();
          processService.validatePhaseTwoOutboxAtStartup();
          // After the outbox: an application which configured a remote BPMS without a store
          // hears about the store first, because that is the more specific gap.
          processService.validateTransactionRunnerAtStartup();
          processService.validateTaskDeliveryLogAtStartup();
          // Last: it asks the stores which the two checks above resolved. So an application
          // which needs neither store does not have to create one just to be asked about
          // it.
          processService.validatePersistedAdapterIdsAtStartup();
        });

  }

  /**
   * The process service of EVERY BPMN process id the workflow service classes of this
   * aggregate declare, the primary one first.
   * <p>
   * Everything which can be configured per workflow can also be configured for a secondary id
   * or an id which is only declared: its prioritized adapters, its outbox, and what its
   * leftovers were persisted under. Each of those services asks its own questions. So the
   * startup checks run over this list and not over the primary service alone. The id which a
   * rename leaves behind is exactly the one whose leftovers nobody would look at otherwise.
   *
   * @return The process services, the primary one first. Only the primary one where no
   *         workflow task registry could be resolved
   */
  public List<MigrationProcessService<A>> getProcessServicesOfDeclaredIds() {

    return processServicesOfDeclaredIds != null
        ? processServicesOfDeclaredIds
        : List.of(migrationProcessService);

  }

  /**
   * Stops this process service on graceful shutdown - invoked by the deployment
   * runner after workflow processing of adapters and extensions was stopped
   * (parity with the Spring Boot integration's
   * <code>ProcessServiceSpringBean.stopService()</code>).
   */
  public void stopService() {

    log.info("Stopping process service: {}", migrationProcessService.getWorkflowModuleId());

  }

  @Override
  public A startWorkflow(
      final A workflowAggregate) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflow(workflowAggregate);

  }

  @Override
  public A completeTask(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.completeTask(workflowAggregate, taskId);

  }

  @Override
  public A cancelTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.cancelTask(workflowAggregate, taskId, bpmnErrorCode);

  }

  @Override
  public A completeUserTask(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.completeUserTask(workflowAggregate, taskId);

  }

  @Override
  public A cancelUserTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.cancelUserTask(workflowAggregate, taskId, bpmnErrorCode);

  }

  @Override
  public void sendSignal(
      final String signalName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionExceptionForSignal();
    }

    migrationProcessService.sendSignal(signalName);

  }

  @Override
  public A aggregateChanged(
      final A workflowAggregate) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.aggregateChanged(workflowAggregate, null);

  }

  @Override
  public A aggregateChanged(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }
    if ((taskId == null) || taskId.isBlank()) {
      throw new IllegalArgumentException(
          """
              No task-id given! Use aggregateChanged(aggregate) to push the aggregate at the \
              workflow's global scope, or pass the task-id reported to the @TaskId parameter of the \
              task whose scope should receive the values.""");
    }

    return migrationProcessService.aggregateChanged(workflowAggregate, taskId);

  }

  @Override
  public A correlateMessage(
      final A workflowAggregate,
      final String messageName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.correlateMessage(workflowAggregate, messageName, null);

  }

  @Override
  public A correlateMessage(
      final A workflowAggregate,
      final String messageName,
      final String correlationId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.correlateMessage(workflowAggregate, messageName, correlationId);

  }

  @Override
  public A startWorkflowByMessage(
      final A workflowAggregate,
      final String messageName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflowByMessage(workflowAggregate, messageName);

  }

  /**
   * The viewer and history API is READ-ONLY. It needs no transaction, because nothing is
   * persisted and the aggregate is only asked for its ID. See
   * {@link MigrationProcessService#getProcessDefinitions(Object, String)}.
   */
  @Override
  public List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getProcessDefinitions(workflowAggregate, historyContext);

  }

  @Override
  public java.io.InputStream getBpmnXml(
      final String processDefinitionId) {

    return migrationProcessService.getBpmnXml(processDefinitionId);

  }

  @Override
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getWorkflowHistory(workflowAggregate, historyContext);

  }

  @SuppressWarnings("unchecked")
  private AggregatePersistenceAware<A> getAggregatePersistence() {

    for (AggregatePersistenceAware<?> persistence : persistences) {
      if (getAggregatePersistenceClass().isAssignableFrom(persistence.getClass())) {
        return (AggregatePersistenceAware<A>) persistence;
      }
    }

    throw new IllegalStateException(
        "No persistence for "
            + getAggregatePersistenceClass()
            + " found at runtime. Maybe the class is not defined as a CDI bean?");

  }

  /**
   * The positive form of {@link #noTransactionIsActive()}. It exists because the Spring Boot
   * bean offers it too. What a test or an application asks a process service should not
   * depend on the platform underneath.
   *
   * @return Whether something is open which the aggregate could be persisted in
   */
  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  /**
   * Whether nothing is open which the aggregate could be persisted in. The question goes to
   * the runner which serves this aggregate. An application may store its aggregates in a
   * system which JTA does not cover. Then it has its own unit of work, and the answer of JTA
   * would be wrong for it.
   *
   * @return Whether the caller would write outside a transaction
   */
  public boolean noTransactionIsActive() {

    final var runner = migrationProcessService.getTransactionRunner(null);
    return runner != null
        ? !runner.isTransactionActive()
        : txRegistry.getTransactionKey() == null;

  }

}
