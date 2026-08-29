package io.vanillabp.integration.test.adapter;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Process service of the second mocked adapter TYPE ('dummy2', see
 * {@link DummyAdapters#twoDummyAdapters()}). The adapter ID matches the adapter
 * 'test2' configured in the test's application.yaml files.
 */
@ApplicationScoped
@Unremovable
public class Test2MigratableProcessService implements MigratableProcessService<Object> {

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

}
