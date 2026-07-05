package io.vanillabp.integration.test.adapter;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Process service of the mocked adapter provided by {@link DummyAdapters}. The adapter ID
 * matches the adapter 'test' configured in the test's application.yaml files.
 */
@ApplicationScoped
@Unremovable
public class TestMigratableProcessService implements MigratableProcessService<Object> {

  @Override
  public String getAdapterId() {

    return "test";

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

    return false;

  }

  @Override
  public void startWorkflowPhaseOne(
      final AggregatePersistenceAware<Object> aggregatePersistence,
      final Object workflowAggregate) {

  }

  @Override
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId) {

  }

}
