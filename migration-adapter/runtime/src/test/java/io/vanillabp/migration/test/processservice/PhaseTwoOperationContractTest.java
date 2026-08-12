package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Pins the PERSISTED contract of VanillaBP's core phase-two operations: their names
 * and their idempotency-key derivation rules. Both were guaranteed by construction
 * as long as the operations were enum constants; since they are entries of the
 * operation registry, this test is the guarantee.
 * <p>
 * A failure here means outbox entries written by an earlier version of the
 * application will no longer dispatch or no longer deduplicate. Do not "fix" the
 * test by adjusting the expectation - fix the operation.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoOperationContractTest {

  private static PhaseTwoCall call(
      final PhaseTwoOperation operation,
      final Map<String, String> args) {

    return PhaseTwoCall
        .of(operation, "test-module", "TestProcess", "42", "test-adapter", args);

  }

  @Test
  @DisplayName("The core operations are exactly these, named exactly like this")
  public void coreOperationNamesArePinned() {

    assertEquals(
        List
            .of(
                "START_WORKFLOW",
                "COMPLETE_TASK",
                "CANCEL_TASK",
                "COMPLETE_USER_TASK",
                "CANCEL_USER_TASK",
                "CORRELATE_MESSAGE",
                "START_WORKFLOW_BY_MESSAGE"),
        PhaseTwoOperation.coreOperationNames());

  }

  @Test
  @DisplayName("Starting a workflow deduplicates per module, process and aggregate")
  public void startWorkflowKeyIsPinned() {

    assertEquals(
        "test-module|TestProcess|42",
        call(PhaseTwoOperation.START_WORKFLOW, Map.of()).idempotencyKey().orElseThrow());

    // by message: same key - a workflow is started at most once per aggregate,
    // regardless of the triggering message
    assertEquals(
        "test-module|TestProcess|42",
        call(
            PhaseTwoOperation.START_WORKFLOW_BY_MESSAGE,
            Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived")).idempotencyKey().orElseThrow());

  }

  @Test
  @DisplayName("Task operations deduplicate per task, not per error code")
  public void taskKeysArePinned() {

    final var expected = "test-module|TestProcess|42|task-1";

    assertEquals(
        expected,
        call(
            PhaseTwoOperation.COMPLETE_TASK,
            Map.of(PhaseTwoCall.ARG_TASK_ID, "task-1")).idempotencyKey().orElseThrow());
    assertEquals(
        expected,
        call(
            PhaseTwoOperation.COMPLETE_USER_TASK,
            Map.of(PhaseTwoCall.ARG_TASK_ID, "task-1")).idempotencyKey().orElseThrow());
    assertEquals(
        expected,
        call(
            PhaseTwoOperation.CANCEL_TASK,
            Map
                .of(
                    PhaseTwoCall.ARG_TASK_ID, "task-1",
                    PhaseTwoCall.ARG_BPMN_ERROR_CODE, "Rejected"))
            .idempotencyKey().orElseThrow());
    assertEquals(
        expected,
        call(
            PhaseTwoOperation.CANCEL_USER_TASK,
            Map
                .of(
                    PhaseTwoCall.ARG_TASK_ID, "task-1",
                    PhaseTwoCall.ARG_BPMN_ERROR_CODE, "Rejected"))
            .idempotencyKey().orElseThrow());

  }

  @Test
  @DisplayName("Message correlation deduplicates only with a correlation id")
  public void correlateMessageKeyIsPinned() {

    assertEquals(
        "test-module|TestProcess|42|OrderReceived|correlation-1",
        call(
            PhaseTwoOperation.CORRELATE_MESSAGE,
            Map
                .of(
                    PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived",
                    PhaseTwoCall.ARG_CORRELATION_ID, "correlation-1"))
            .idempotencyKey().orElseThrow());

    // without a correlation id the same message may legitimately be correlated
    // several times - there is no key, and an at-least-once dispatch may
    // double-correlate (documented residual)
    assertTrue(
        call(
            PhaseTwoOperation.CORRELATE_MESSAGE,
            Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived")).idempotencyKey().isEmpty());

  }

  @Test
  @DisplayName("A call rebuilt from a persisted entry carries no key - the store persisted it")
  public void dispatchCallsCarryNoKey() {

    assertTrue(
        PhaseTwoCall
            .forDispatch(
                "START_WORKFLOW", "test-module", "TestProcess", "42", "test-adapter", Map.of())
            .idempotencyKey()
            .isEmpty());

  }

}
