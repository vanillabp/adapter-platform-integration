package io.vanillabp.integration.adapter.migration.processervice;

import java.util.List;
import java.util.Map;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.intergration.adapter.migration.spi.AggregatePersistenceAware;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessServicePhaseTwo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MigrationProcessService<A> {

  @Getter
  private final String workflowModuleId;

  @Getter
  private final String bpmnProcessId;

  @Getter
  private final Class<A> workflowAggregateClass;

  /**
   * Map of known adapters. The key is the adapter id, the value is the adapter type.
   */
  @Getter
  private final Map<String, String> adapters;

  /**
   * List of adapter ids sorted by priority.
   */
  @Getter
  private final List<String> prioritizedAdapters;

  private final List<MigratableProcessService<A>> adapterProcessServices;

  private final AggregatePersistenceAware<A> aggregatePersistenceSupport;

  private final TransactionOutbox transactionOutbox;

  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final TransactionOutbox transactionOutbox) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.workflowAggregateClass = workflowAggregateClass;
    this.adapters = properties.getAdapters();
    this.prioritizedAdapters = properties.getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId);
    this.aggregatePersistenceSupport = aggregatePersistenceSupport;
    this.adapterProcessServices = prioritizedAdapters
        .stream()
        .flatMap(adapterId -> processServices
            .stream()
            .filter(processService -> processService.getAdapterId().equals(adapterId))
            .findFirst()
            .stream())
        .toList();
    this.transactionOutbox = transactionOutbox;

  }

  public boolean needsTransactionForStartingWorkflows() {

    return adapterProcessServices
        .getFirst()
        .needsTwoPhaseCommitForStartingWorkflows();

  }

  public A startWorkflow(
      final A workflowAggregate) {

    // persist to get ID in case of @Id @GeneratedValue
    // or force optimistic locking exceptions before running
    // the workflow if aggregate was already persisted before
    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);

    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);

    final var adapter = adapterProcessServices
        .getFirst();

    adapter.startWorkflowPhaseOne(aggregatePersistenceSupport, workflowAggregate);

    if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
      transactionOutbox
          .with()
          .schedule(MigratableProcessServicePhaseTwo.class)
          .startWorkflowPhaseTwo(
              workflowModuleId,
              bpmnProcessId,
              adapter.getAdapterId(),
              aggregateId);
    }

    return attachedAggregate;

  }

  public void startWorkflowPhaseTwo(
      final String adapterId,
      final Object workflowAggregateId) {

    final var adapter = adapterProcessServices
        .stream()
        .filter(processService -> processService.getAdapterId().equals(adapterId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No adapter found for ID '%s'! Maybe it was available in a previous version your software?"
                .formatted(adapterId)));

    adapter.startWorkflowPhaseTwo(workflowAggregateId);

  }

  /**
   * Connect to BPMS after bean creation
   */
  public void initialize() {

  }

}
