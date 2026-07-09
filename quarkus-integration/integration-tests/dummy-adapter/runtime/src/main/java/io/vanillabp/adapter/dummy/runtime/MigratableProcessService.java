package io.vanillabp.adapter.dummy.runtime;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.inject.Instance;

public class MigratableProcessService<A> implements io.vanillabp.integration.adapter.spi.MigratableProcessService<A> {

  private final String adapterId;

  private final boolean needsTwoPhaseCommitForStartingWorkflows;

  private final Instance<DummyPhaseTwoListener> phaseTwoListeners;

  public MigratableProcessService(
      final String adapterId,
      final boolean needsTwoPhaseCommitForStartingWorkflows,
      final Instance<DummyPhaseTwoListener> phaseTwoListeners) {

    this.adapterId = adapterId;
    this.needsTwoPhaseCommitForStartingWorkflows = needsTwoPhaseCommitForStartingWorkflows;
    this.phaseTwoListeners = phaseTwoListeners;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final Object workflowAggregateId,
      final String taskId) {

    return WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final Object workflowAggregateId) {

    return WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  @Override
  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    return needsTwoPhaseCommitForStartingWorkflows;

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.startedWorkflowPhaseTwo(workflowAggregateId));
    }

  }

}
