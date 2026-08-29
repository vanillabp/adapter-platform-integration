package io.vanillabp.integration.test.adapter;

import java.util.List;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
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

      /**
       * Serves every operation and does nothing: this double is here for the election
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
