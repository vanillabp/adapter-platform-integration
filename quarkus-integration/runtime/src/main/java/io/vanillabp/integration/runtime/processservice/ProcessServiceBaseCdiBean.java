package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.adapter.spi.ProcessServicePhaseTwo;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import lombok.Getter;

public abstract class ProcessServiceBaseCdiBean<A> implements ProcessService<A>, ProcessServicePhaseTwo {

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
   * The process services of the VanillaBP adapters available at runtime.
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.adapter.spi.MigratableProcessService<?>> migratableProcessServices;

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

  @Getter
  MigrationProcessService<A> migrationProcessService;

  public abstract Class<AggregatePersistenceAware<A>> getAggregatePersistenceClass();

  public abstract Class<A> getWorkflowAggregateClass();

  public abstract String getBpmnProcessId();

  @PostConstruct
  public void initialize() {

    @SuppressWarnings("unchecked")
    final List<io.vanillabp.integration.adapter.spi.MigratableProcessService<A>> processServices = migratableProcessServices
        .stream()
        .map(processService -> (io.vanillabp.integration.adapter.spi.MigratableProcessService<A>) processService)
        .toList();

    this.migrationProcessService = new MigrationProcessService<>(
        getWorkflowModuleId(), getBpmnProcessId(), getWorkflowAggregateClass(), properties, getAggregatePersistence(), processServices, phaseTwoOutbox
            .isResolvable() ? phaseTwoOutbox.get() : null);

  }

  public A startWorkflow(
      A workflowAggregate) {

    if (migrationProcessService.needsTransactionForStartingWorkflows() && noTransactionIsActive()) {
      throw new RuntimeException(
          "No transaction active available! Add run 'startWorkflow' only having a local transaction active.");
    }

    return migrationProcessService.startWorkflow(workflowAggregate);

  }

  @Override
  @Transactional
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    migrationProcessService.startWorkflowPhaseTwo(workflowAggregateId);

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
