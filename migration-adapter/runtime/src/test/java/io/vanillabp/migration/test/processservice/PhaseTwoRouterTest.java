package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoRouterTest {

  @Mock
  private MigrationProcessService<Object> processService;

  private final PhaseTwoRouter testee = new PhaseTwoRouter();

  @BeforeEach
  public void mockProcessService() {

    when(processService.getWorkflowModuleId()).thenReturn("test-module");
    when(processService.getBpmnProcessId()).thenReturn("TestProcess");

  }

  @Test
  @DisplayName("Dispatch converts the serialized aggregate ID exactly once and calls the typed method")
  public void dispatchConvertsIdAndCallsTypedMethod() {

    when(processService.convertAggregateId("42")).thenReturn(42L);

    testee.register(processService);

    testee.dispatch(new PhaseTwoCall(
        PhaseTwoOperation.START_WORKFLOW, "test-module", "TestProcess", "42", "test-adapter", Map.of()));

    verify(processService).startWorkflowPhaseTwo(42L, "test-adapter");

  }

  @Test
  @DisplayName("Dispatch for an unknown BPMN process fails with a guiding message")
  public void dispatchFailsOnUnknownProcess() {

    testee.register(processService);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.dispatch(new PhaseTwoCall(
            PhaseTwoOperation.START_WORKFLOW, "test-module", "RemovedProcess", "42", "test-adapter", Map.of())));

    // the message has to name the process, the module and that the entry stays
    // visible in the outbox store
    assertTrue(exception.getMessage().contains("RemovedProcess"));
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("outbox"));

  }

}
