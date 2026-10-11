package io.vanillabp.integration.test.adapter;

import java.util.List;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Produces a process service for adapter id 'test2' through a bean of type
 * <code>List&lt;MigratableProcessService&gt;</code>. An adapter uses this shape for ids which
 * it only learns at runtime. A CDI producer cannot return N element beans for N configured
 * ids. So an adapter produces ONE List bean. The platform collects it and flattens it
 * ALONGSIDE the element beans. Here {@link TestMigratableProcessService} is the element
 * bean, and it serves id 'test'.
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

      /**
       * Serves every operation and does nothing. This test double exists for the election
       * and the deployment, not for what an operation sends to a BPMS.
       */
      @Override
      public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Object>> phaseOperations() {
        final var operations = new java.util.HashMap<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Object>>();
        io.vanillabp.integration.spi.PhaseOperation.CORE_OPERATIONS
            .forEach(
                operation -> operations
                    .put(
                        operation,
                        io.vanillabp.integration.adapter.spi.PhaseOperationHandler
                            .of(request -> {
                            }, request -> {
                            })));
        return operations;
      }

      @Override
      public WorkflowAwareness awarenessOfTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public WorkflowAwareness awarenessOfWorkflow(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final io.vanillabp.integration.spi.AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {
        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

    });

  }

}
