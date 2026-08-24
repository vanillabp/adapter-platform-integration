package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.AggregateIdRoundTrip;
import io.vanillabp.integration.spi.AggregateIdTypes;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Startup check of the aggregate-ID round-trip: the ID crosses the phase-two outbox
 * serialized as a String, so an ID
 * type which does not convert from/to String losslessly has to fail the startup
 * with a guiding message. The ID type is detected as the interface default of
 * {@code AggregatePersistenceAware#getAggregateIdType()} does.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateIdRoundTripValidationTest {

  private static void validate(
      final Class<?> workflowAggregateClass) {

    AggregateIdRoundTrip.validateIdTypeConvertible(
        workflowAggregateClass,
        AggregateIdTypes
            .determineIdType(workflowAggregateClass)
            .orElse(null));

  }

  static class LongIdAggregate {
    @SuppressWarnings("unused")
    private Long id;
  }

  static class UuidIdAggregate {
    @SuppressWarnings("unused")
    private UUID id;
  }

  static class DateIdAggregate {
    @SuppressWarnings("unused")
    private Date id;
  }

  static class NoIdAggregate {
    @SuppressWarnings("unused")
    private String something;
  }

  @Test
  @DisplayName("Supported ID types pass the startup check")
  public void supportedIdTypesPass() {

    assertDoesNotThrow(() -> validate(LongIdAggregate.class));
    assertDoesNotThrow(() -> validate(UuidIdAggregate.class));

  }

  @Test
  @DisplayName("An undeterminable ID type passes (custom persistence owns the serialized form)")
  public void undeterminableIdTypePasses() {

    assertDoesNotThrow(() -> validate(NoIdAggregate.class));

  }

  @Test
  @DisplayName("An unsupported ID type fails naming the aggregate class and the remedy")
  public void unsupportedIdTypeFailsWithGuidingMessage() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> validate(DateIdAggregate.class));

    assertTrue(exception.getMessage().contains(DateIdAggregate.class.getName()));
    assertTrue(exception.getMessage().contains(Date.class.getName()));
    // phrases spanning the text block's line breaks: guards against broken
    // continuations gluing words together or inserting indentation whitespace
    assertTrue(exception.getMessage().contains("converted from/to String!"));
    assertTrue(exception.getMessage().contains("round-trip losslessly"));
    assertTrue(exception.getMessage().contains("AggregatePersistenceAware"));
    assertFalse(exception.getMessage().contains("  "), "message must not contain consecutive spaces");

  }

}
