package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.RunningActivation;
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
public class PhaseOperationContractTest {

  private static PhaseTwoCall call(
      final PhaseOperation operation,
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
                "START_WORKFLOW_BY_MESSAGE",
                "SEND_SIGNAL",
                "AGGREGATE_CHANGED"),
        PhaseOperation.coreOperationNames());

  }

  @Test
  @DisplayName("Starting a workflow deduplicates per module, process and aggregate")
  public void startWorkflowKeyIsPinned() {

    assertEquals(
        "START_WORKFLOW|test-module|TestProcess|42",
        call(PhaseOperation.START_WORKFLOW, Map.of()).idempotencyKey().orElseThrow());

    // by message: same key, the plain start's name included - a workflow is started at
    // most once per aggregate, regardless of the triggering message
    assertEquals(
        "START_WORKFLOW|test-module|TestProcess|42",
        call(
            PhaseOperation.START_WORKFLOW_BY_MESSAGE,
            Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived")).idempotencyKey().orElseThrow());

  }

  @Test
  @DisplayName("Task operations deduplicate per task, not per error code")
  public void taskKeysArePinned() {

    assertEquals(
        "COMPLETE_TASK|test-module|TestProcess|42|task-1",
        call(
            PhaseOperation.COMPLETE_TASK,
            Map.of(PhaseTwoCall.ARG_TASK_ID, "task-1")).idempotencyKey().orElseThrow());
    assertEquals(
        "COMPLETE_USER_TASK|test-module|TestProcess|42|task-1",
        call(
            PhaseOperation.COMPLETE_USER_TASK,
            Map.of(PhaseTwoCall.ARG_TASK_ID, "task-1")).idempotencyKey().orElseThrow());
    // the BPMN error code is NOT part of the key: one task is cancelled once
    assertEquals(
        "CANCEL_TASK|test-module|TestProcess|42|task-1",
        call(
            PhaseOperation.CANCEL_TASK,
            Map
                .of(
                    PhaseTwoCall.ARG_TASK_ID, "task-1",
                    PhaseTwoCall.ARG_BPMN_ERROR_CODE, "Rejected"))
            .idempotencyKey().orElseThrow());
    assertEquals(
        "CANCEL_USER_TASK|test-module|TestProcess|42|task-1",
        call(
            PhaseOperation.CANCEL_USER_TASK,
            Map
                .of(
                    PhaseTwoCall.ARG_TASK_ID, "task-1",
                    PhaseTwoCall.ARG_BPMN_ERROR_CODE, "Rejected"))
            .idempotencyKey().orElseThrow());

  }

  @Test
  @DisplayName("Completing and cancelling one task no longer share a key")
  public void everyOperationOnOneTaskHasItsOwnKey() {

    final var keys = java.util.stream.Stream
        .of(
            PhaseOperation.COMPLETE_TASK,
            PhaseOperation.CANCEL_TASK,
            PhaseOperation.COMPLETE_USER_TASK,
            PhaseOperation.CANCEL_USER_TASK)
        .map(operation -> call(operation, Map.of(PhaseTwoCall.ARG_TASK_ID, "task-1"))
            .idempotencyKey()
            .orElseThrow())
        .collect(java.util.stream.Collectors.toSet());

    assertEquals(4, keys.size(), "one task id, four operations, four keys: "
        + keys);

  }

  @Test
  @DisplayName("Message correlation deduplicates only with a correlation id")
  public void correlateMessageKeyIsPinned() {

    assertEquals(
        "CORRELATE_MESSAGE|test-module|TestProcess|42|OrderReceived|correlation-1",
        call(
            PhaseOperation.CORRELATE_MESSAGE,
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
            PhaseOperation.CORRELATE_MESSAGE,
            Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived")).idempotencyKey().isEmpty());

  }

  @Test
  @DisplayName("An activation in the args names the correlation key, and only that key")
  public void correlateMessageKeyCarriesTheActivationFromTheArgs() {

    // the value is read from the CALL, not from the thread: the derivation stays a pure
    // function of what a store persists, and the same args carry the activation to the
    // adapter at dispatch time. Which is why opening a scope alone changes nothing here
    final var withActivation = Map
        .of(
            PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived",
            PhaseTwoCall.ARG_CORRELATION_ID, "correlation-1",
            PhaseTwoCall.ARG_ACTIVATION_ID, "element-instance-99");
    assertEquals(
        "CORRELATE_MESSAGE|test-module|TestProcess|42|OrderReceived|correlation-1|element-instance-99",
        call(PhaseOperation.CORRELATE_MESSAGE, withActivation).idempotencyKey().orElseThrow());

    // an entry written before this existed carries no such arg and keeps its key, which
    // is what lets it dispatch across the upgrade
    final var withoutActivation = Map
        .of(
            PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived",
            PhaseTwoCall.ARG_CORRELATION_ID, "correlation-1");
    assertEquals(
        "CORRELATE_MESSAGE|test-module|TestProcess|42|OrderReceived|correlation-1",
        call(PhaseOperation.CORRELATE_MESSAGE, withoutActivation).idempotencyKey().orElseThrow());

    try (var activation = RunningActivation.of("element-instance-99")) {
      assertEquals(
          "CORRELATE_MESSAGE|test-module|TestProcess|42|OrderReceived|correlation-1",
          call(PhaseOperation.CORRELATE_MESSAGE, withoutActivation).idempotencyKey().orElseThrow(),
          "the scope is read where a correlation is PLANNED, not where its key is derived");
    }

  }

  @Test
  @DisplayName("No other operation is told about an activation")
  public void noOtherKeyCarriesAnActivation() {

    // these deduplicate ACROSS activations on purpose: a workflow is started at most once
    // per aggregate whichever activation asks, and a task id already names one activation
    // of one element
    assertEquals(
        "START_WORKFLOW|test-module|TestProcess|42",
        call(PhaseOperation.START_WORKFLOW, Map.of(PhaseTwoCall.ARG_ACTIVATION_ID, "element-instance-99"))
            .idempotencyKey()
            .orElseThrow());
    assertEquals(
        "COMPLETE_TASK|test-module|TestProcess|42|task-1",
        call(
            PhaseOperation.COMPLETE_TASK,
            Map
                .of(
                    PhaseTwoCall.ARG_TASK_ID, "task-1",
                    PhaseTwoCall.ARG_ACTIVATION_ID, "element-instance-99"))
            .idempotencyKey()
            .orElseThrow());
    // and keyless stays keyless: an activation must not start deduplicating what is
    // deliberately not deduplicated
    assertTrue(
        call(
            PhaseOperation.CORRELATE_MESSAGE,
            Map
                .of(
                    PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived",
                    PhaseTwoCall.ARG_ACTIVATION_ID, "element-instance-99"))
            .idempotencyKey()
            .isEmpty());
    assertTrue(
        call(
            PhaseOperation.SEND_SIGNAL,
            Map
                .of(
                    PhaseTwoCall.ARG_SIGNAL_NAME, "Recalled",
                    PhaseTwoCall.ARG_ACTIVATION_ID, "element-instance-99"))
            .idempotencyKey()
            .isEmpty());

  }

  @Test
  @DisplayName("A broadcast signal has nothing to deduplicate by")
  public void sendSignalHasNoKey() {

    assertTrue(
        PhaseTwoCall
            .of(
                PhaseOperation.SEND_SIGNAL, "test-module", "TestProcess", null, "test-adapter", Map
                    .of(PhaseTwoCall.ARG_SIGNAL_NAME, "OrderReceived"))
            .idempotencyKey()
            .isEmpty());

  }

  @Test
  @DisplayName("Pushing a changed aggregate has no key - the values are read at dispatch time")
  public void aggregateChangedHasNoKey() {

    assertTrue(
        call(PhaseOperation.AGGREGATE_CHANGED, Map.of()).idempotencyKey().isEmpty());
    assertTrue(
        call(PhaseOperation.AGGREGATE_CHANGED, Map.of(PhaseTwoCall.ARG_TASK_ID, "task-1"))
            .idempotencyKey()
            .isEmpty());

  }

  @Test
  @DisplayName("A key too long for the stores is hashed, and the hash is pinned")
  public void anOversizedKeyIsHashed() {

    // 300 characters of aggregate id push the derived key past the 250 characters
    // gruelbox accepts as a unique request id
    final var aggregateId = "a".repeat(300);
    final var key = PhaseTwoCall
        .of(
            PhaseOperation.CORRELATE_MESSAGE, "test-module", "TestProcess", aggregateId, null, Map
                .of(
                    PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived",
                    PhaseTwoCall.ARG_CORRELATION_ID, "correlation-1"))
        .idempotencyKey()
        .orElseThrow();

    // pinned as a literal: entries of a running installation are looked up by this
    // string, so a changed digest, prefix or boundary would stop matching silently
    assertEquals(
        "sha256:bfd70402b37726c004502c1e170fa5d6e38b74c63acf3923a555410fd24df939",
        key);
    assertTrue(
        key.length() <= PhaseTwoCall.MAX_IDEMPOTENCY_KEY_LENGTH,
        "the hash fits what every store can hold");

  }

  @Test
  @DisplayName("An aggregate id no store can hold is refused where the call is built")
  public void anUnstorableAggregateIdIsRefused() {

    final var aggregateId = "a".repeat(PhaseTwoCall.MAX_AGGREGATE_ID_LENGTH + 1);

    final var exception = org.junit.jupiter.api.Assertions
        .assertThrows(
            IllegalArgumentException.class,
            () -> PhaseTwoCall
                .of(PhaseOperation.START_WORKFLOW, "test-module", "TestProcess", aggregateId, "test-adapter", Map
                    .of()));

    // the message has to name the column and the length, because it replaces a
    // driver-level truncation error in the middle of the caller's transaction
    assertTrue(exception.getMessage().contains("AGGREGATE_ID"), exception.getMessage());
    assertTrue(exception.getMessage().contains("1025"), exception.getMessage());
    assertTrue(exception.getMessage().contains("1024"), exception.getMessage());
    assertTrue(exception.getMessage().contains("START_WORKFLOW"), exception.getMessage());

  }

  @Test
  @DisplayName("An aggregate id up to the column's width is accepted")
  public void theLongestStorableAggregateIdIsAccepted() {

    assertTrue(
        PhaseTwoCall
            .of(
                PhaseOperation.START_WORKFLOW, "test-module", "TestProcess", "a"
                    .repeat(PhaseTwoCall.MAX_AGGREGATE_ID_LENGTH),
                "test-adapter", Map.of())
            .idempotencyKey()
            .isPresent());

  }

  @Test
  @DisplayName("Args no store can hold are refused where the call is built")
  public void unstorableArgsAreRefused() {

    // 700 percent signs are 700 characters raw and 2100 encoded, so only the
    // serialized form exceeds the column - a check against the raw value would let
    // exactly this case through
    final var correlationId = "%".repeat(700);

    final var exception = org.junit.jupiter.api.Assertions
        .assertThrows(
            IllegalArgumentException.class,
            () -> PhaseTwoCall
                .of(PhaseOperation.CORRELATE_MESSAGE, "test-module", "TestProcess", "42", "test-adapter", Map
                    .of(
                        PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived",
                        PhaseTwoCall.ARG_CORRELATION_ID, correlationId)));

    assertTrue(correlationId.length() < PhaseTwoCall.MAX_ARGS_LENGTH, "raw value fits, encoded does not");
    // the message replaces a driver-level truncation in the middle of the caller's
    // transaction, so it names the column, both lengths and the argument to change
    assertTrue(exception.getMessage().contains("ARGS"), exception.getMessage());
    assertTrue(exception.getMessage().contains("2048"), exception.getMessage());
    assertTrue(exception.getMessage().contains(PhaseTwoCall.ARG_CORRELATION_ID), exception.getMessage());
    assertTrue(exception.getMessage().contains("CORRELATE_MESSAGE"), exception.getMessage());

  }

  @Test
  @DisplayName("Args up to the column's width are accepted")
  public void theLongestStorableArgsAreAccepted() {

    // "messageName=" plus the value is what is stored, so the value which just fits is
    // shorter than the column by the length of the key and the separator
    final var messageName = "m".repeat(PhaseTwoCall.MAX_ARGS_LENGTH - (PhaseTwoCall.ARG_MESSAGE_NAME.length() + 1));

    // no correlation id, so this call carries no idempotency key by design - what is
    // asserted here is that it is built at all, with its args intact
    assertEquals(
        messageName,
        PhaseTwoCall
            .of(
                PhaseOperation.CORRELATE_MESSAGE, "test-module", "TestProcess", "42", "test-adapter", Map
                    .of(PhaseTwoCall.ARG_MESSAGE_NAME, messageName))
            .args()
            .get(PhaseTwoCall.ARG_MESSAGE_NAME));

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
