package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What asking for the state of the event does to the call which asks: the auditing id
 * travels in the arguments, it never reaches the idempotency key, and the operations
 * which write to the BPMS cannot ask for it at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoCallAuditingIdTest {

  private static final PhaseOperation REPORTING = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.of("%s|%s".formatted(call.operation(), call.workflowAggregateId())))
      .build();

  /**
   * An operation deriving its key from EVERY argument, which is the rule the auditing id
   * would break if it were added before the derivation.
   */
  private static final PhaseOperation REPORTING_KEYED_OVER_ALL_ARGS = PhaseOperation
      .extensionOperation("sample:NOTIFY_ALL")
      .idempotencyKey(call -> Optional.of("%s|%s".formatted(call.operation(), call.args())))
      .build();

  private static PhaseTwoCall reportingCall() {

    return PhaseTwoCall
        .of(REPORTING, "module", "Process", "42", null, Map.of("event", "created"));

  }

  @Test
  @DisplayName("A call which asks for nothing carries no auditing id")
  public void aCallWhichAsksForNothingCarriesNone() {

    final var call = reportingCall();

    assertNull(call.auditingId());
    assertEquals(Map.of("event", "created"), call.args());

  }

  @Test
  @DisplayName("A call asking for the state of the event carries the auditing id in its arguments")
  public void theAuditingIdTravelsInTheArguments() {

    final var call = reportingCall().askingForTheStateOfTheEvent("rev-7");

    assertEquals("rev-7", call.auditingId());
    assertEquals("rev-7", call.args().get(PhaseTwoCall.ARG_AUDITING_ID));
    // the arguments the caller passed are still there, unchanged
    assertEquals("created", call.args().get("event"));

  }

  @Test
  @DisplayName("An application without auditing answers null, and the call stays what it was")
  public void anAuditingIdOfNullChangesNothing() {

    final var call = reportingCall();

    assertSame(call, call.askingForTheStateOfTheEvent(null));

  }

  @Test
  @DisplayName("The auditing id is added after the key was derived, so it deduplicates nothing away")
  public void theAuditingIdIsNotPartOfTheIdempotencyKey() {

    final var plain = PhaseTwoCall
        .of(REPORTING_KEYED_OVER_ALL_ARGS, "module", "Process", "42", null, Map.of("event", "created"));
    final var asking = plain.askingForTheStateOfTheEvent("rev-7");

    assertEquals(plain.idempotencyKey(), asking.idempotencyKey());

  }

  @Test
  @DisplayName("A dispatched call carries the auditing id the store persisted")
  public void theStoreHandsTheAuditingIdBack() {

    final var scheduled = reportingCall().askingForTheStateOfTheEvent("rev-7");

    final var dispatched = PhaseTwoCall
        .forDispatch(
            scheduled.operation(), scheduled.workflowModuleId(), scheduled.bpmnProcessId(), scheduled
                .workflowAggregateId(),
            null, PhaseTwoCall.deserializeArgs(PhaseTwoCall.serializeArgs(scheduled.args())));

    assertEquals("rev-7", dispatched.auditingId());

  }

  @Test
  @DisplayName("An operation of VanillaBP's own cannot ask for the state of the event")
  public void anOperationWritingToTheBpmsIsRefused() {

    final var completion = PhaseTwoCall
        .of(
            PhaseOperation.COMPLETE_TASK, "module", "Process", "42", null, Map
                .of(PhaseTwoCall.ARG_TASK_ID, "task-1"));

    final var refused = assertThrows(
        IllegalArgumentException.class,
        () -> completion.askingForTheStateOfTheEvent("rev-7"));

    // the message names the operation, where it belongs, and why the BPMS hears the
    // state of now
    assertTrue(refused.getMessage().contains("COMPLETE_TASK"), refused.getMessage());
    assertTrue(refused.getMessage().contains("Process"), refused.getMessage());
    assertTrue(refused.getMessage().contains("module"), refused.getMessage());
    assertTrue(refused.getMessage().contains("goes on"), refused.getMessage());

  }

  @Test
  @DisplayName("An auditing id no store can hold is refused where it was answered")
  public void anAuditingIdTooLongIsRefused() {

    final var tooLong = "r".repeat(PhaseTwoCall.MAX_AUDITING_ID_LENGTH + 1);

    final var refused = assertThrows(
        IllegalArgumentException.class,
        () -> reportingCall().askingForTheStateOfTheEvent(tooLong));

    assertTrue(refused.getMessage().contains("getAuditingId"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains(String.valueOf(PhaseTwoCall.MAX_AUDITING_ID_LENGTH)),
        refused.getMessage());

  }

  @Test
  @DisplayName("Arguments which only just fitted are measured again with the auditing id")
  public void theArgumentsAreMeasuredAgain() {

    final var nearlyFull = PhaseTwoCall
        .of(
            REPORTING, "module", "Process", "42", null, Map
                .of("event", "e".repeat(PhaseTwoCall.MAX_ARGS_LENGTH - 10)));

    final var refused = assertThrows(
        IllegalArgumentException.class,
        () -> nearlyFull.askingForTheStateOfTheEvent("rev-7"));

    assertTrue(refused.getMessage().contains("ARGS column"), refused.getMessage());

  }

  @Test
  @DisplayName("A payload and the state of the event are two answers to one question, and both fit")
  public void aCallMayCarryBothAPayloadAndAnAuditingId() {

    final var call = PhaseTwoCall
        .of(
            REPORTING, "module", "Process", "42", null, Map.of("event", "created"), "the state"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .askingForTheStateOfTheEvent("rev-7");

    assertTrue(call.hasPayload());
    assertEquals("rev-7", call.auditingId());
    assertTrue(call.payloadReference() != null, "the payload kept its reference");

  }

}
