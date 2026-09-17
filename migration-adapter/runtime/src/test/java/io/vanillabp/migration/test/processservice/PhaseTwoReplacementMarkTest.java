package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the word a call says about replacing does to that call: it changes nothing a
 * store persists, it reaches no derivation rule, only an extension may say it, and a
 * store which never learned replacing says so instead of keeping the older state
 * quietly.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoReplacementMarkTest {

  private static final PhaseOperation OF_AN_EXTENSION = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.of("%s|%s".formatted(call.operation(), call.workflowAggregateId())))
      .build();

  /**
   * An operation deriving its key from EVERY argument, which is the rule the mark would
   * break if it travelled in the arguments.
   */
  private static final PhaseOperation KEYED_OVER_ALL_ARGS = PhaseOperation
      .extensionOperation("sample:NOTIFY_ALL")
      .idempotencyKey(call -> Optional.of("%s|%s".formatted(call.operation(), call.args())))
      .build();

  /**
   * A store of an application's own which never heard of replacing: it implements the
   * one method the contract demands and inherits everything else.
   */
  private static class AStoreWhichCannotReplace implements PhaseTwoOutbox {

    private PhaseTwoCall scheduled;

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      scheduled = call;
      return false;

    }

  }

  private static byte[] payloadOf(
      final String content) {

    return content.getBytes(StandardCharsets.UTF_8);

  }

  @Test
  @DisplayName("A call says nothing about replacing unless it is asked to")
  public void aPlainCallReplacesNothing() {

    final var call = PhaseTwoCall
        .of(OF_AN_EXTENSION, "module", "Process", "42", null, Map.of("event", "created"));

    assertFalse(call.replacesWhatIsStillWaiting());

  }

  @Test
  @DisplayName("The word changes the planning and nothing a store persists")
  public void theMarkLeavesTheEntryAlone() {

    final var plain = PhaseTwoCall
        .of(
            KEYED_OVER_ALL_ARGS,
            "module",
            "Process",
            "42",
            null,
            Map.of("event", "created"),
            payloadOf("the state"));
    final var replacing = plain.replacingWhatIsStillWaiting();

    assertTrue(replacing.replacesWhatIsStillWaiting());
    // the key is derived while the call is built, long before anybody says this word,
    // so a rule reading every argument sees the same arguments either way
    assertEquals(plain.idempotencyKey(), replacing.idempotencyKey());
    assertEquals(plain.args(), replacing.args());
    assertEquals(plain.operation(), replacing.operation());
    assertEquals(plain.payloadReference(), replacing.payloadReference());
    assertArrayEquals(plain.payload(), replacing.payload());
    // and it IS a difference between two calls, so nothing treats them as one
    assertNotEquals(plain, replacing);

  }

  @Test
  @DisplayName("A call rebuilt for its dispatch replaces nothing")
  public void aCallRebuiltForItsDispatchReplacesNothing() {

    final var call = PhaseTwoCall
        .forDispatch("sample:NOTIFY", "module", "Process", "42", null, Map.of("event", "created"));

    assertFalse(call.replacesWhatIsStillWaiting());
    assertFalse(call.withPayload(payloadOf("the state")).replacesWhatIsStillWaiting());

  }

  @Test
  @DisplayName("The bytes a store read for an entry keep the word the call said")
  public void thePayloadReadAtDispatchTimeKeepsTheMark() {

    final var replacing = PhaseTwoCall
        .of(OF_AN_EXTENSION, "module", "Process", "42", null, Map.of("event", "created"))
        .replacingWhatIsStillWaiting();

    assertTrue(replacing.withPayload(payloadOf("the state")).replacesWhatIsStillWaiting());

  }

  @Test
  @DisplayName("An operation of VanillaBP itself may not replace, and is told where it asked")
  public void anOperationOfVanillaBpMayNotReplace() {

    final var start = PhaseTwoCall
        .of(PhaseOperation.START_WORKFLOW, "module", "Process", "42", "camunda8", Map.of());

    final var refused = assertThrows(IllegalArgumentException.class, start::replacingWhatIsStillWaiting);
    assertTrue(refused.getMessage().contains(PhaseOperation.START_WORKFLOW.name()));
    assertTrue(refused.getMessage().contains("Process"));
    assertTrue(refused.getMessage().contains("module"));
    assertTrue(refused.getMessage().contains("my-extension:NOTIFY"));

  }

  @Test
  @DisplayName("A store which cannot replace names itself instead of keeping the older state quietly")
  public void aStoreWhichCannotReplaceSaysSo() {

    final var store = new AStoreWhichCannotReplace();
    final var call = PhaseTwoCall
        .of(OF_AN_EXTENSION, "module", "Process", "42", null, Map.of("event", "created"))
        .replacingWhatIsStillWaiting();

    final var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final var recorded = new ListAppender<ILoggingEvent>();
    recorded.start();
    root.addAppender(recorded);
    final boolean scheduled;
    try {
      scheduled = store.scheduleReplacingWhatIsStillWaiting(call);
    } finally {
      root.detachAppender(recorded);
    }

    assertFalse(scheduled);
    // it did what it always did - the call reached its schedule unchanged
    assertEquals(call, store.scheduled);
    final var reported = recorded.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(reported.contains(AStoreWhichCannotReplace.class.getName()), reported);
    assertTrue(reported.contains("the OLDER one"), reported);

  }

}
