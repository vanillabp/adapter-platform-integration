package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseOperationNotSupported;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter which cannot serve an operation every adapter has to serve is refused
 * while the application boots. The map an adapter answers IS its statement about what
 * it serves, so a forgotten operation is caught before a workflow waits for it.
 * <p>
 * An adapter which still runs on the compatibility bridge serves every core operation
 * by definition, which the second test holds: the check must not refuse the three
 * adapters VanillaBP ships today.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterOperationsAtStartupTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * An adapter in the new shape, serving exactly the operations it is given.
   */
  private static class HandlerAdapter implements MigratableProcessService<Object> {

    private final List<PhaseOperation> served;

    HandlerAdapter(
        final List<PhaseOperation> served) {
      this.served = served;
    }

    @Override
    public String getAdapterId() {
      return "first-adapter";
    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<Object>>();
      served
          .forEach(
              operation -> operations
                  .put(
                      operation,
                      PhaseOperationHandler
                          .of(
                              request -> {
                              },
                              request -> {
                              })));
      return operations;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
      return WorkflowAwareness.ACTIVE;
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  private static List<PhaseOperation> everyRequiredOperation() {

    return PhaseOperation.CORE_OPERATIONS
        .stream()
        .filter(PhaseOperation::requiredOfEveryAdapter)
        .toList();

  }

  private MigrationProcessService<Object> processService(
      final MigratableProcessService<Object> adapter) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("first-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("first-adapter"))
        .build();
    properties.validateAndLink();

    return new MigrationProcessService<Object>(
        MODULE, PROCESS, Object.class, properties, new SendSignalTest.PersistenceStub(), List.of(adapter), null);

  }

  @Test
  @DisplayName("An adapter contributing a handler per required operation boots")
  public void handlersAreEnough() {

    processService(new HandlerAdapter(everyRequiredOperation()))
        .validateAdapterOperationsAtStartup();

  }

  @Test
  @DisplayName("An adapter still running on the compatibility bridge boots as well")
  public void theCompatibilityBridgeCounts() {

    processService(new SendSignalTest.RecordingAdapter("first-adapter"))
        .validateAdapterOperationsAtStartup();

  }

  @Test
  @DisplayName("An adapter serving neither way is refused, naming it and what is missing")
  public void anAdapterWithoutOperationsIsRefused() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> processService(new HandlerAdapter(List.of()))
            .validateAdapterOperationsAtStartup());

    assertTrue(exception.getMessage().contains("first-adapter"), exception.getMessage());
    assertTrue(exception.getMessage().contains("COMPLETE_TASK"), exception.getMessage());
    assertTrue(exception.getMessage().contains("START_WORKFLOW"), exception.getMessage());
    assertTrue(exception.getMessage().contains("PhaseOperationHandler"), exception.getMessage());

  }

  @Test
  @DisplayName("An operation the BPMS has nothing like is no reason to refuse the boot")
  public void anOptionalOperationMayBeMissing() {

    final var adapter = new HandlerAdapter(everyRequiredOperation());

    // signals and aggregate pushes are not required of every adapter, and this one
    // serves neither
    processService(adapter).validateAdapterOperationsAtStartup();

    // the application asking for one is where it learns so
    final var exception = assertThrowsExactly(
        PhaseOperationNotSupported.class,
        () -> processService(adapter).sendSignal("OrderReceived"));

    assertTrue(exception.getMessage().contains("SEND_SIGNAL"), exception.getMessage());
    assertTrue(exception.getMessage().contains("replace the signal by a message"), exception.getMessage());

  }

}
