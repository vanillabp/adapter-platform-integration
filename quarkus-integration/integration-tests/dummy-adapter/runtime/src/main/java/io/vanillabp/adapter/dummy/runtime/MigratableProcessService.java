package io.vanillabp.adapter.dummy.runtime;

import io.vanillabp.integration.adapter.spi.AggregatePersistenceAware;

public class MigratableProcessService<A> implements io.vanillabp.integration.adapter.spi.MigratableProcessService<A> {

  private final String adapterId;

  public MigratableProcessService(
      final String adapterId) {

    this.adapterId = adapterId;

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

    return false;

  }

  @Override
  public void startWorkflowPhaseOne(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

  }

  @Override
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId) {

  }

}
