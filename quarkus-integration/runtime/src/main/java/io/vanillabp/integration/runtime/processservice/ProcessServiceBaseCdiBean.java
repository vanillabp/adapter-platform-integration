package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutboxAware;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.Getter;

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

  @Getter
  MigrationProcessService<A> migrationProcessService;

  public abstract Class<AggregatePersistenceAware<A>> getAggregatePersistenceClass();

  public abstract Class<A> getWorkflowAggregateClass();

  public abstract String getBpmnProcessId();

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
    this.migrationProcessService = new MigrationProcessService<>(
        getWorkflowModuleId(), getBpmnProcessId(), getWorkflowAggregateClass(), properties, getAggregatePersistence(), processServices, new QuarkusPhaseTwoOutboxResolver(
            phaseTwoOutboxAwares, phaseTwoOutboxes, outboxProperties
                .jdbc()
                .enabled(), outboxProperties
                    .mongo()
                    .enabled()));

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter.isResolvable()) {
      phaseTwoRouter
          .get()
          .register(migrationProcessService);
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

  @Override
  public A startWorkflow(
      final A workflowAggregate) {

    if (migrationProcessService.needsTwoPhaseCommitForStartingWorkflows() && noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflow(workflowAggregate);

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
