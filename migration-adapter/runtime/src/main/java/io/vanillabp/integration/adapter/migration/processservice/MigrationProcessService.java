package io.vanillabp.integration.adapter.migration.processservice;

import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
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

  /**
   * The outbox used to schedule phase two of a two-phase workflow start. Provided by
   * the platform integration; may be <code>null</code> if the platform does not
   * provide one - in this case starting workflows via adapters which report
   * {@link MigratableProcessService#needsTwoPhaseCommitForStartingWorkflows()} fails.
   */
  private final PhaseTwoOutbox phaseTwoOutbox;

  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final PhaseTwoOutbox phaseTwoOutbox) {

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
    if (this.adapterProcessServices.isEmpty()) {
      throw new IllegalStateException(
          ("No MigratableProcessService found for any of the prioritized adapters '%s' "
              + "configured for BPMN process '%s' of workflow module '%s'!")
              .formatted(
                  String.join("', '", prioritizedAdapters),
                  bpmnProcessId,
                  workflowModuleId));
    }
    this.phaseTwoOutbox = phaseTwoOutbox;

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

    adapter.startWorkflowPhaseOne(workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate);

    if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
      if (phaseTwoOutbox == null) {
        throw new IllegalStateException(
            """
                Adapter '%s' requires a two-phase commit for starting workflows of BPMN process '%s' \
                of workflow module '%s', but no PhaseTwoOutbox is available! \
                Provide an implementation of io.vanillabp.integration.adapter.spi.PhaseTwoOutbox \
                (e.g. by using JPA or MongoDB for persistence of aggregates which enables one of \
                the default implementations of the platform integration)."""
                .formatted(
                    adapter.getAdapterId(),
                    bpmnProcessId,
                    workflowModuleId));
      }
      phaseTwoOutbox.scheduleStartWorkflow(
          workflowModuleId,
          bpmnProcessId,
          aggregateId,
          adapter.getAdapterId());
    }

    return attachedAggregate;

  }

  /**
   * Executes phase two of starting a workflow, dispatched by the
   * {@link PhaseTwoRouter} after the local transaction of
   * {@link #startWorkflow(Object)} was committed. The adapter elected in phase one
   * was persisted with the outbox entry and is used here - there is no re-election
   * from the then-current priorities.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (in its original type)
   * @param adapterId The ID of the adapter elected in phase one
   */
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId,
      final String adapterId) {

    final var adapter = adapterProcessServices
        .stream()
        .filter(processService -> processService.getAdapterId().equals(adapterId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                Cannot execute phase two of starting the workflow of aggregate '%s': adapter '%s' is \
                not (or no longer) configured for BPMN process '%s' of workflow module '%s'! The \
                outbox entry is stale - the adapter was probably removed from the configuration \
                (property 'vanillabp.prioritized-adapters' or its module-/workflow-level \
                overrides) after the entry was scheduled. Restore the adapter's configuration or \
                remove the entry from the outbox store."""
                .formatted(
                    workflowAggregateId,
                    adapterId,
                    bpmnProcessId,
                    workflowModuleId)));

    adapter.startWorkflowPhaseTwo(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

}
