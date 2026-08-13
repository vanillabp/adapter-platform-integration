package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.spi.PhaseTwoOperationDispatch;
import io.vanillabp.integration.spi.PhaseTwoOperationRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The rules keeping the operation registry unambiguous: an operation is registered
 * exactly once, extensions namespace their operations, and the core's names are
 * reserved.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoOperationRegistryTest {

  private static final PhaseTwoOperationDispatch NOOP = (
      call,
      previouslyAttempted) -> {
  };

  private final PhaseTwoOperationRegistry testee = new PhaseTwoOperationRegistry();

  private static PhaseTwoOperation extensionOperation(
      final String name) {

    return PhaseTwoOperation.extensionOperation(name, call -> Optional.empty());

  }

  @Test
  @DisplayName("A registered extension operation is found by its name")
  public void extensionOperationIsFound() {

    final var operation = extensionOperation("my-extension:NOTIFY");
    testee.register(operation, NOOP);

    assertEquals(operation, testee.find("my-extension:NOTIFY").orElseThrow());
    assertTrue(testee.dispatchFor("my-extension:NOTIFY").isPresent());
    assertTrue(testee.find("my-extension:UNKNOWN").isEmpty());
    assertEquals(java.util.List.of("my-extension:NOTIFY"), testee.registeredNames());

  }

  @Test
  @DisplayName("An extension operation without a namespace is rejected guiding")
  public void unnamespacedOperationIsRejected() {

    final var exception = assertThrowsExactly(
        IllegalArgumentException.class,
        () -> extensionOperation("NOTIFY"));

    // the message has to name the offending operation and show the expected shape
    assertTrue(exception.getMessage().contains("NOTIFY"));
    assertTrue(exception.getMessage().contains("my-extension:MY_OPERATION"));

  }

  @Test
  @DisplayName("An extension claiming a core operation's name is rejected guiding")
  public void coreOperationNameIsReserved() {

    // built through the plain constructor: 'extensionOperation' would already
    // reject it for the missing namespace - the point here is the reservation
    final var squatter = new PhaseTwoOperation(
        PhaseTwoOperation.START_WORKFLOW.name(), call -> Optional.empty());

    final var exception = assertThrowsExactly(
        IllegalArgumentException.class,
        () -> testee.register(squatter, NOOP));

    assertTrue(exception.getMessage().contains("START_WORKFLOW"));
    assertTrue(exception.getMessage().contains("reserved"));

  }

  @Test
  @DisplayName("Registering a non-core operation as a core operation is rejected guiding")
  public void registerCoreOperationChecksTheOperation() {

    final var exception = assertThrowsExactly(
        IllegalArgumentException.class,
        () -> testee.registerCoreOperation(extensionOperation("my-extension:NOTIFY"), NOOP));

    assertTrue(exception.getMessage().contains("my-extension:NOTIFY"));
    assertTrue(exception.getMessage().contains("START_WORKFLOW"));

  }

  @Test
  @DisplayName("Registering the same operation twice fails guiding")
  public void duplicateRegistrationFails() {

    testee.register(extensionOperation("my-extension:NOTIFY"), NOOP);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.register(extensionOperation("my-extension:NOTIFY"), NOOP));

    assertTrue(exception.getMessage().contains("my-extension:NOTIFY"));
    assertTrue(exception.getMessage().contains("twice"));

  }

  @Test
  @DisplayName("A blank operation name is rejected")
  public void blankNameIsRejected() {

    assertThrowsExactly(
        IllegalArgumentException.class,
        () -> new PhaseTwoOperation(" ", call -> Optional.empty()));

  }

}
