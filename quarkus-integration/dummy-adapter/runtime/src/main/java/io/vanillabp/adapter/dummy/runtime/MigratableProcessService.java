package io.vanillabp.adapter.dummy.runtime;

import io.vanillabp.spi.process.AggregatePersistenceAware;

public class MigratableProcessService<A> implements io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService<A> {

  @Override
  public String getAdapterId() {

    return "";

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
