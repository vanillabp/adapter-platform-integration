package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class ProcessServiceBaseCdiBean<A> extends ProcessServiceBase<A> {

  @Inject
  MigrationAdapterProperties properties;

  /**
   * The persistences available at runtime. This is injected using {@link Instance} because the services
   * annotated by {@link io.vanillabp.spi.service.WorkflowService} may implement {@link AggregatePersistenceAware}
   * what causes cycle dependencies since this bean is typically also injected into the service.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> persistences;

  /**
   * The process services of the VanillaBP adapters available at runtime. The
   * convention is one <i>element</i> bean per adapter (never a bean of type
   * <code>List&lt;MigratableProcessService&gt;</code>) so several adapter types
   * coexist in one application.
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.adapter.spi.MigratableProcessService<?>> migratableProcessServices;

  /**
   * Additionally accepted shape: beans of type
   * <code>List&lt;MigratableProcessService&lt;Object&gt;&gt;</code>, flattened into
   * the collected process services. Synthetic beans created from runtime
   * configuration (one process service per configured adapter id,
   * adapter-config-model story 26d) cannot be registered as individual element
   * beans on Quarkus - a single List bean per adapter is the documented shape
   * there. The element type parameter is LITERALLY {@code Object} by convention
   * (CDI's parameterized-type matching of nested wildcards is not reliable across
   * modes, so the platform looks the beans up with the exact type).
   */
  @Inject
  @Any
  Instance<List<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>>> migratableProcessServiceLists;

  /**
   * The outboxes available at runtime, resolved per aggregate (mixed persistence,
   * dedicated outboxes) via {@link QuarkusPhaseTwoOutboxResolver}. Unsatisfied if no
   * implementation is available (e.g. no datasource configured) - in this case only
   * adapters not requiring a two-phase commit can start workflows.
   */
  @Inject
  @Any
  Instance<PhaseTwoOutbox> phaseTwoOutboxes;

  /**
   * Application-provided attributions of aggregates to outboxes (required in
   * mixed-persistence setups, optional otherwise).
   */
  @Inject
  @Any
  Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  /**
   * The core-owned router dispatching committed phase-two outbox entries. This bean
   * registers itself (including the aggregate-ID converter) at bean creation.
   */
  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  /**
   * The core-owned registry of <code>&#64;WorkflowTask</code> handlers. Resolved via
   * {@link Instance} for the same cycle reasons as the other collaborators.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry> workflowTaskRegistry;

  @Getter
  MigrationProcessService<A> migrationProcessService;

  public abstract Class<AggregatePersistenceAware<A>> getAggregatePersistenceClass();

  public abstract Class<A> getWorkflowAggregateClass();

  public abstract String getBpmnProcessId();

  /**
   * All (workflow module, workflow service class, BPMN process ID) combinations of
   * the aggregate this process service serves, determined at build time: every
   * class declaring the aggregate contributes all its declared BPMN process IDs
   * ({@code @WorkflowService.bpmnProcess} and {@code secondaryBpmnProcesses}).
   * Encoding: entries {@code <module>|<class name>|<bpmn process id>} joined by
   * {@code ';'} (generated as a class-file constant - Gizmo-friendly).
   */
  public abstract String getWorkflowTaskRegistrations();

  @PostConstruct
  public void initialize() {

    // collect element beans plus flattened List beans (see field javadoc)
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

    final var outboxProperties = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(QuarkusMigrationAdapterProperties.class)
        .outbox();
    final var phaseTwoOutboxResolver = new QuarkusPhaseTwoOutboxResolver(
        phaseTwoOutboxAwares, phaseTwoOutboxes, outboxProperties
            .jdbc()
            .enabled(), outboxProperties
                .mongo()
                .enabled());
    this.migrationProcessService = new MigrationProcessService<>(
        getWorkflowModuleId(), getBpmnProcessId(), getWorkflowAggregateClass(), properties, getAggregatePersistence(), processServices, phaseTwoOutboxResolver);

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter.isResolvable()) {
      phaseTwoRouter
          .get()
          .register(migrationProcessService);
    }

    registerWorkflowTaskHandlers(processServices, phaseTwoOutboxResolver);

  }

  /**
   * Registers all workflow service classes of this aggregate under all BPMN process
   * IDs they declare (build-time facts, see {@link #getWorkflowTaskRegistrations()})
   * with the core's workflow-task registry. Secondary BPMN processes get their own
   * {@link MigrationProcessService} (registered for phase-two routing, too); the
   * primary one is reused.
   */
  private void registerWorkflowTaskHandlers(
      final List<io.vanillabp.integration.adapter.spi.MigratableProcessService<A>> processServices,
      final QuarkusPhaseTwoOutboxResolver phaseTwoOutboxResolver) {

    if (!workflowTaskRegistry.isResolvable()) {
      return;
    }
    final var registry = workflowTaskRegistry.get();
    final var processServicesByKey = new java.util.HashMap<String, MigrationProcessService<A>>();
    processServicesByKey.put(
        "%s|%s".formatted(getWorkflowModuleId(), getBpmnProcessId()),
        migrationProcessService);
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
            final var secondaryProcessService = new MigrationProcessService<>(
                moduleId, bpmnProcessId, getWorkflowAggregateClass(), properties, getAggregatePersistence(), processServices, phaseTwoOutboxResolver);
            if (phaseTwoRouter.isResolvable()) {
              phaseTwoRouter
                  .get()
                  .register(secondaryProcessService);
            }
            return secondaryProcessService;
          });
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

  }

  /**
   * Startup validation (observer methods are inherited by the generated
   * process-service beans): if the first-priority adapter of this process requires
   * a two-phase commit, the phase-two outbox is resolved AT STARTUP - a missing
   * outbox fails the boot with a guiding message instead of surfacing at the first
   * workflow start.
   *
   * @param event The startup event observed
   */
  public void onStart(
      @Observes final StartupEvent event) {

    migrationProcessService.validatePhaseTwoOutboxAtStartup();

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

    if (migrationProcessService.needsTwoPhaseCommitForStartingWorkflows() && noTransactionIsActive()) {
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

  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  public boolean noTransactionIsActive() {

    return txRegistry.getTransactionKey() == null;

  }

}
