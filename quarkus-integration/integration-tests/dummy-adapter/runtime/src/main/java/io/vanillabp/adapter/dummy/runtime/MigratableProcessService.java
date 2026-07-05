package io.vanillabp.adapter.dummy.runtime;

import io.vanillabp.integration.adapter.spi.AggregatePersistenceAware;
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
  public Boolean isTaskActive(
      final String taskId) {

    return null;

  }

  @Override
  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    return needsTwoPhaseCommitForStartingWorkflows;

  }

  @Override
  public void startWorkflowPhaseOne(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

  }

  @Override
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.startedWorkflowPhaseTwo(workflowAggregateId));
    }

  }

}
