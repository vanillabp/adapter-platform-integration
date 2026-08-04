package io.vanillabp.integration.test.adapter;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Process service of the mocked adapter provided by {@link DummyAdapters}, requiring
 * a TWO-PHASE COMMIT for starting workflows (like a remote BPMS) - used to test the
 * startup validation of the phase-two outbox. The adapter ID matches the adapter
 * 'test' configured in the test's application.yaml files.
 */
@ApplicationScoped
@Unremovable
public class TwoPhaseTestMigratableProcessService implements MigratableProcessService<Object> {

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

    return true;

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<Object> aggregatePersistence,
      final Object workflowAggregate) {

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<Object> aggregatePersistence,
      final Object workflowAggregateId) {

  }

  @Override
  public void completeTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<Object> aggregatePersistence,
      final Object workflowAggregate,
      final String taskId) {
  }

  @Override
  public void completeTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<Object> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {
  }

  @Override
  public void cancelTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<Object> aggregatePersistence,
      final Object workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {
  }

  @Override
  public void cancelTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<Object> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {
  }

}
