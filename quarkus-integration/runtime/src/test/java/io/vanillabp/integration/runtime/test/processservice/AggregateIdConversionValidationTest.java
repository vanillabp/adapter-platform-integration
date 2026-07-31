package io.vanillabp.integration.runtime.test.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.runtime.processservice.AggregateIdConversion;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Startup check of the aggregate-ID round-trip (story 26c): the ID crosses the
 * phase-two outbox serialized as a String, so an ID type which does not convert
 * from/to String losslessly has to fail the startup with a guiding message.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateIdConversionValidationTest {

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

    assertDoesNotThrow(() -> AggregateIdConversion.validateIdTypeConvertible(LongIdAggregate.class));
    assertDoesNotThrow(() -> AggregateIdConversion.validateIdTypeConvertible(UuidIdAggregate.class));

  }

  @Test
  @DisplayName("An undeterminable ID type passes (custom persistence owns the serialized form)")
  public void undeterminableIdTypePasses() {

    assertDoesNotThrow(() -> AggregateIdConversion.validateIdTypeConvertible(NoIdAggregate.class));

  }

  @Test
  @DisplayName("An unsupported ID type fails naming the aggregate class and the remedy")
  public void unsupportedIdTypeFailsWithGuidingMessage() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> AggregateIdConversion.validateIdTypeConvertible(DateIdAggregate.class));

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
