package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoRouterTest {

  @Mock
  private MigrationProcessService<Object> processService;

  private final PhaseTwoRouter testee = new PhaseTwoRouter();

  /**
   * Registers the mocked process service for <code>test-module/TestProcess</code> -
   * done per test instead of in a setup method, because the tests around the
   * operation registry need no process service at all.
   */
  private void registerProcessService() {

    when(processService.getWorkflowModuleId()).thenReturn("test-module");
    when(processService.getBpmnProcessId()).thenReturn("TestProcess");

    testee.register(processService);

  }

  @Test
  @DisplayName("Dispatch runs inside the transaction the platform provides (story 67)")
  public void dispatchRunsInsideTheProvidedTransaction() {

    when(processService.getWorkflowModuleId()).thenReturn("test-module");
    when(processService.getBpmnProcessId()).thenReturn("TestProcess");
    when(processService.convertAggregateId("42")).thenReturn(42L);

    final var runner = new RecordingTransactionRunner();
    final var router = new PhaseTwoRouter(runner);
    router.register(processService);

    router.dispatch(PhaseTwoCall
        .of(
            PhaseTwoOperation.START_WORKFLOW, "test-module", "TestProcess", "42", "test-adapter", Map.of()));

    assertTrue(runner.dispatchedInsideTransaction, "phase two ran without the transaction the platform provided");

  }

  /**
   * Stands in for a platform's transaction runner and remembers whether the work it
   * received actually reached the process service.
   */
  private class RecordingTransactionRunner implements TransactionRunner {

    private boolean insideTransaction;

    private boolean dispatchedInsideTransaction;

    @Override
    public <T> T requireTransaction(
        final java.util.function.Supplier<T> work) {

      insideTransaction = true;
      try {
        final var result = work.get();
        dispatchedInsideTransaction = insideTransaction && Mockito.mockingDetails(processService).getInvocations()
            .stream().anyMatch(
                invocation -> invocation.getMethod().getName().equals("startWorkflowPhaseTwo"));
        return result;
      } finally {
        insideTransaction = false;
      }

    }

    @Override
    public <T> T requireNew(
        final java.util.function.Supplier<T> work) {

      throw new UnsupportedOperationException("phase two has to use requireTransaction");

    }

    @Override
    public <T> T inCurrent(
        final java.util.function.Supplier<T> work) {

      throw new UnsupportedOperationException("phase two has to use requireTransaction");

    }

    @Override
    public boolean isRollbackOnly() {

      return false;

    }

    @Override
    public boolean isConcurrentModification(
        final Throwable failure) {

      return false;

    }

  }

  @Test
  @DisplayName("Dispatch converts the serialized aggregate ID exactly once and calls the typed method")
  public void dispatchConvertsIdAndCallsTypedMethod() {

    when(processService.convertAggregateId("42")).thenReturn(42L);

    registerProcessService();

    testee.dispatch(PhaseTwoCall
        .of(
            PhaseTwoOperation.START_WORKFLOW, "test-module", "TestProcess", "42", "test-adapter", Map.of()));

    verify(processService).startWorkflowPhaseTwo(42L, "test-adapter", false);

  }

  @Test
  @DisplayName("Dispatch for an unknown BPMN process fails with a guiding message")
  public void dispatchFailsOnUnknownProcess() {

    registerProcessService();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.dispatch(PhaseTwoCall
            .of(
                PhaseTwoOperation.START_WORKFLOW, "test-module", "RemovedProcess", "42", "test-adapter", Map.of())));

    // the message has to name the process, the module and that the entry stays
    // visible in the outbox store
    assertTrue(exception.getMessage().contains("RemovedProcess"));
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("outbox"));

  }

  @Test
  @DisplayName("An operation of an extension is dispatched to the extension's own handler")
  public void extensionOperationIsDispatchedToItsHandler() {

    final var operation = PhaseTwoOperation
        .extensionOperation("my-extension:NOTIFY", call -> java.util.Optional.empty());
    final var dispatched = new java.util.concurrent.atomic.AtomicReference<PhaseTwoCall>();
    testee
        .getOperations()
        .register(
            operation,
            (
                call,
                previouslyAttempted) -> dispatched.set(call));

    // no process service is registered: an extension operation is NOT routed
    // through the aggregate-ID-to-adapter election of the core operations
    testee.dispatch(PhaseTwoCall
        .of(operation, "test-module", "TestProcess", "42", null, Map.of("event", "created")));

    assertTrue(dispatched.get() != null);
    assertTrue(dispatched.get().operation().equals("my-extension:NOTIFY"));
    assertTrue(dispatched.get().args().get("event").equals("created"));

  }

  @Test
  @DisplayName("Dispatching an unregistered operation fails guiding and leaves the entry alone")
  public void dispatchFailsOnUnknownOperation() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.dispatch(PhaseTwoCall
            .forDispatch(
                "gone-extension:NOTIFY", "test-module", "TestProcess", "42", null, Map.of())));

    // the message has to name the unknown operation, list what IS registered and
    // say that the entry stays in the store
    assertTrue(exception.getMessage().contains("gone-extension:NOTIFY"));
    assertTrue(exception.getMessage().contains("START_WORKFLOW"));
    assertTrue(exception.getMessage().contains("outbox store"));

  }

}
