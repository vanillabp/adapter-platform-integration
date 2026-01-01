package io.vanillabp.integration.adapter.migration.processervice;

import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService;
import io.vanillabp.spi.process.AggregatePersistenceAware;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MigrationProcessService<A> {

  @Getter
  protected final String workflowModuleId;

  @Getter
  protected final String bpmnProcessId;

  @Getter
  protected final Class<A> workflowAggregateClass;

  @Getter
  protected final Map<String, String> adapters;

  @Getter
  protected final List<String> prioritizedAdapters;

  protected final List<MigratableProcessService<A>> processServices;

  protected final AggregatePersistenceAware<A> aggregatePersistenceSupport;

  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.workflowAggregateClass = workflowAggregateClass;
    this.adapters = properties.getAdapters();
    this.prioritizedAdapters = properties.getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId);
    this.aggregatePersistenceSupport = aggregatePersistenceSupport;
    this.processServices = processServices;

  }

  public A startWorkflow(
      final A workflowAggregate,
      final boolean afterTransaction) {

    // persist to get ID in case of @Id @GeneratedValue
    // or force optimistic locking exceptions before running
    // the workflow if aggregate was already persisted before
    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);

    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);

    // TODO: start workflow by using the right adapter

    return attachedAggregate;

  }

  /**
   * Connect to BPMS after bean creation
   */
  public void initialize() {

  }

}
