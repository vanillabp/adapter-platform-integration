package io.vanillabp.integration.runtime.processservice;

import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.Getter;

public abstract class ProcessServiceBaseCdiBean<A> implements ProcessService<A> {

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
   * <code>List&lt;MigratableProcessService&gt;</code>, flattened into the collected
   * process services. Synthetic beans created from runtime configuration (one
   * process service per configured adapter id, adapter-config-model story 26d)
   * cannot always be registered as individual element beans on Quarkus - a single
   * List bean per adapter is the documented escape hatch there.
   */
  @Inject
  @Any
  Instance<List<io.vanillabp.integration.adapter.spi.MigratableProcessService<?>>> migratableProcessServiceLists;

  /**
   * The outbox used to schedule phase two of a two-phase workflow start. Unsatisfied
   * if no implementation is available (e.g. no datasource configured) - in this case
   * only adapters not requiring a two-phase commit can start workflows.
   */
  @Inject
  @Any
  Instance<PhaseTwoOutbox> phaseTwoOutbox;

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

    this.migrationProcessService = new MigrationProcessService<>(
        getWorkflowModuleId(), getBpmnProcessId(), getWorkflowAggregateClass(), properties, getAggregatePersistence(), processServices, phaseTwoOutbox
            .isResolvable() ? phaseTwoOutbox.get() : null);

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter.isResolvable()) {
      phaseTwoRouter
          .get()
          .register(migrationProcessService, buildAggregateIdConverter());
    }

  }

  /**
   * Builds the converter turning the serialized (String) workflow-aggregate ID of an
   * outbox entry back into the aggregate's ID type (determined by reflection - see
   * {@link AggregateIdConversion}). If the type cannot be determined (custom
   * persistence), the String is passed through unchanged.
   *
   * @return The converter registered with the {@link PhaseTwoRouter}
   */
  private Function<String, Object> buildAggregateIdConverter() {

    final var aggregateIdType = AggregateIdConversion
        .determineIdType(getWorkflowAggregateClass());
    return serializedAggregateId -> aggregateIdType
        .<Object>map(idType -> AggregateIdConversion.convert(serializedAggregateId, idType))
        .orElse(serializedAggregateId);

  }

  public A startWorkflow(
      A workflowAggregate) {

    if (migrationProcessService.needsTransactionForStartingWorkflows() && noTransactionIsActive()) {
      throw new RuntimeException(
          "No transaction active available! Add run 'startWorkflow' only having a local transaction active.");
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
