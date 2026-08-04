package io.vanillabp.integration.test.adapter;

import java.util.List;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Produces a process service for adapter id 'test2' via a bean of type
 * <code>List&lt;MigratableProcessService&gt;</code> - the per-adapter-id shape of the
 * adapter-config-model story (26d): a CDI producer cannot yield N element beans for N
 * runtime-configured ids, so adapters produce ONE List bean which the platform's
 * collection point flattens ALONGSIDE element beans (here:
 * {@link TestMigratableProcessService} serving id 'test' as an element bean).
 */
@ApplicationScoped
@Unremovable
public class Test2ListProcessServiceProducer {

  @Produces
  @Unremovable
  public List<MigratableProcessService<Object>> test2ProcessServices() {

    return List.of(new MigratableProcessService<>() {

      @Override
      public String getAdapterId() {
        return "test2";
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

    });

  }

}
