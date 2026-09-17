package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a payload does to the call it travels with: it gets a reference of its own, the
 * reference never reaches the idempotency key, and a payload too large for any store is
 * refused where it was passed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoPayloadTest {

  private static final PhaseOperation KEYED = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.of("%s|%s".formatted(call.operation(), call.workflowAggregateId())))
      .build();

  /**
   * An operation deriving its key from EVERY argument, which is the rule the reference
   * would break if it were added before the derivation.
   */
  private static final PhaseOperation KEYED_OVER_ALL_ARGS = PhaseOperation
      .extensionOperation("sample:NOTIFY_ALL")
      .idempotencyKey(call -> Optional.of("%s|%s".formatted(call.operation(), call.args())))
      .build();

  private static byte[] payloadOf(
      final String content) {

    return content.getBytes(StandardCharsets.UTF_8);

  }

  @Test
  @DisplayName("A call without a payload is what it always was")
  public void aCallWithoutAPayloadCarriesNoReference() {

    final var call = PhaseTwoCall
        .of(KEYED, "module", "Process", "42", null, Map.of("event", "created"));

    assertFalse(call.hasPayload());
    assertNull(call.payload());
    assertNull(call.payloadReference());
    assertEquals(Map.of("event", "created"), call.args());

  }

  @Test
  @DisplayName("A payload gets a reference of its own, and the arguments carry it")
  public void aPayloadGetsAReference() {

    final var call = PhaseTwoCall
        .of(KEYED, "module", "Process", "42", null, Map.of("event", "created"), payloadOf("the state"));

    assertTrue(call.hasPayload());
    assertArrayEquals(payloadOf("the state"), call.payload());
    assertEquals(call.payloadReference(), call.args().get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE));
    assertEquals(36, call.payloadReference().length());

    final var second = PhaseTwoCall
        .of(KEYED, "module", "Process", "42", null, Map.of("event", "created"), payloadOf("the state"));
    assertNotEquals(call.payloadReference(), second.payloadReference());

  }

  @Test
  @DisplayName("The reference is added after the key was derived, so it deduplicates nothing away")
  public void theReferenceIsNotPartOfTheIdempotencyKey() {

    final var withoutPayload = PhaseTwoCall
        .of(KEYED_OVER_ALL_ARGS, "module", "Process", "42", null, Map.of("event", "created"));
    final var withPayload = PhaseTwoCall
        .of(
            KEYED_OVER_ALL_ARGS, "module", "Process", "42", null, Map.of("event", "created"),
            payloadOf("the state"));

    assertEquals(withoutPayload.idempotencyKey(), withPayload.idempotencyKey());

  }

  @Test
  @DisplayName("A dispatched call carries the bytes the store read for its reference")
  public void aDispatchedCallCarriesWhatTheStoreRead() {

    final var scheduled = PhaseTwoCall
        .of(KEYED, "module", "Process", "42", null, Map.of("event", "created"), payloadOf("the state"));

    final var dispatched = PhaseTwoCall
        .forDispatch(
            scheduled.operation(), scheduled.workflowModuleId(), scheduled.bpmnProcessId(), scheduled
                .workflowAggregateId(),
            null, scheduled.args(), payloadOf("the state"));

    assertEquals(scheduled.payloadReference(), dispatched.payloadReference());
    assertArrayEquals(payloadOf("the state"), dispatched.payload());
    // the bytes decide equality, not the array's identity: the same entry read twice
    // gives two arrays and one call
    final var readAgain = PhaseTwoCall
        .forDispatch(
            scheduled.operation(), scheduled.workflowModuleId(), scheduled.bpmnProcessId(), scheduled
                .workflowAggregateId(),
            null, scheduled.args(), payloadOf("the state"));
    assertEquals(dispatched, readAgain);
    assertEquals(dispatched.hashCode(), readAgain.hashCode());
    assertNotEquals(dispatched, readAgain.withPayload(payloadOf("another state")));

  }

  @Test
  @DisplayName("The call holds a copy, so nobody changes bytes a store is about to write")
  public void thePayloadIsCopied() {

    final var bytes = payloadOf("the state");
    final var call = PhaseTwoCall
        .of(KEYED, "module", "Process", "42", null, Map.of(), bytes);

    bytes[0] = '!';
    assertArrayEquals(payloadOf("the state"), call.payload());

    call.payload()[0] = '!';
    assertArrayEquals(payloadOf("the state"), call.payload());

  }

  @Test
  @DisplayName("A payload larger than any store holds is refused where it was passed")
  public void aPayloadTooLargeIsRefused() {

    final var tooLarge = new byte[PhaseTwoCall.MAX_PAYLOAD_SIZE + 1];

    final var failure = assertThrows(
        IllegalArgumentException.class,
        () -> PhaseTwoCall.of(KEYED, "module", "Process", "42", null, Map.of(), tooLarge));

    assertTrue(failure.getMessage().contains("sample:NOTIFY"), failure.getMessage());
    assertTrue(failure.getMessage().contains("Process"), failure.getMessage());
    assertTrue(failure.getMessage().contains("module"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains(String.valueOf(PhaseTwoCall.MAX_PAYLOAD_SIZE)),
        failure.getMessage());

  }

  @Test
  @DisplayName("A payload of exactly the limit is allowed")
  public void aPayloadOfTheLimitIsAllowed() {

    final var call = PhaseTwoCall
        .of(KEYED, "module", "Process", "42", null, Map.of(), new byte[PhaseTwoCall.MAX_PAYLOAD_SIZE]);

    assertEquals(PhaseTwoCall.MAX_PAYLOAD_SIZE, call.payload().length);

  }

  @Test
  @DisplayName("A log line names the payload by its length and never by its bytes")
  public void theStringFormNamesTheLengthOnly() {

    final var call = PhaseTwoCall
        .of(KEYED, "module", "Process", "42", null, Map.of(), payloadOf("a secret"));

    assertTrue(call.toString().contains("8 bytes"), call.toString());
    assertFalse(call.toString().contains("a secret"), call.toString());
    assertTrue(
        PhaseTwoCall
            .of(KEYED, "module", "Process", "42", null, Map.of())
            .toString()
            .contains("payload=none"));

  }

}
