package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.AggregateIdRoundTrip;
import io.vanillabp.integration.spi.AggregateIdTypes;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class AggregateIdRoundTripTest {

  static class FieldAnnotatedAggregate {

    // private on purpose: private fields have to be considered, too
    @SuppressWarnings("unused")
    @jakarta.persistence.Id
    private Long key;

  }

  static class GetterAnnotatedAggregate {

    @SuppressWarnings("unused")
    private Long key;

    // JPA property access: the ID annotation sits on the getter, not the field
    @jakarta.persistence.Id
    public Long getKey() {
      return key;
    }

  }

  static class GetterIdAggregate {

    @SuppressWarnings("unused")
    private UUID internal;

    public UUID getId() {
      return internal;
    }

  }

  static class IdNamedFieldAggregate {

    @SuppressWarnings("unused")
    private UUID id;

  }

  static class NoIdAggregate {

    @SuppressWarnings("unused")
    private String content;

  }

  static class InheritedIdAggregate extends IdNamedFieldAggregate {
  }

  @Test
  @DisplayName("An annotated (also private) field wins over everything else")
  public void idTypeFromAnnotatedField() {

    assertEquals(Optional.of(Long.class), AggregateIdTypes.determineIdType(FieldAnnotatedAggregate.class));

  }

  @Test
  @DisplayName("An annotated getter is evaluated secondarily (JPA property access)")
  public void idTypeFromAnnotatedGetter() {

    assertEquals(Optional.of(Long.class), AggregateIdTypes.determineIdType(GetterAnnotatedAggregate.class));

  }

  @Test
  @DisplayName("The ID type is determined by a (private) field named 'id' (also inherited)")
  public void idTypeFromIdNamedField() {

    assertEquals(Optional.of(UUID.class), AggregateIdTypes.determineIdType(IdNamedFieldAggregate.class));
    assertEquals(Optional.of(UUID.class), AggregateIdTypes.determineIdType(InheritedIdAggregate.class));

  }

  @Test
  @DisplayName("A getter named 'getId' is the last fallback")
  public void idTypeFromGetIdGetter() {

    assertEquals(Optional.of(UUID.class), AggregateIdTypes.determineIdType(GetterIdAggregate.class));

  }

  @Test
  @DisplayName("Without any ID field the type cannot be determined")
  public void noIdField() {

    assertTrue(AggregateIdTypes.determineIdType(NoIdAggregate.class).isEmpty());

  }

  @Test
  @DisplayName("Supported simple types are converted from their serialized form")
  public void supportedTypesAreConverted() {

    assertEquals("42", AggregateIdRoundTrip.convert("42", String.class));
    assertEquals(42L, AggregateIdRoundTrip.convert("42", Long.class));
    assertEquals(42, AggregateIdRoundTrip.convert("42", Integer.class));
    assertEquals((short) 42, AggregateIdRoundTrip.convert("42", Short.class));
    assertEquals((byte) 42, AggregateIdRoundTrip.convert("42", Byte.class));
    assertEquals(42.5d, AggregateIdRoundTrip.convert("42.5", Double.class));
    assertEquals(42.5f, AggregateIdRoundTrip.convert("42.5", Float.class));
    assertEquals(Boolean.TRUE, AggregateIdRoundTrip.convert("true", Boolean.class));
    assertEquals(new BigInteger("42"), AggregateIdRoundTrip.convert("42", BigInteger.class));
    assertEquals(new BigDecimal("42.5"), AggregateIdRoundTrip.convert("42.5", BigDecimal.class));
    final var uuid = UUID.randomUUID();
    assertEquals(uuid, AggregateIdRoundTrip.convert(uuid.toString(), UUID.class));
    assertNull(AggregateIdRoundTrip.convert(null, Long.class));

  }

  @Test
  @DisplayName("Unsupported types and unparsable values are passed through as strings")
  public void unsupportedTypesArePassedThrough() {

    assertEquals(
        "custom-id",
        AggregateIdRoundTrip.convert("custom-id", NoIdAggregate.class));
    assertEquals(
        "not-a-number",
        AggregateIdRoundTrip.convert("not-a-number", Long.class));

  }

}
