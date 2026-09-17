package io.vanillabp.integration.adapter.migration.workflowstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How a value the BPMS reported reaches an attribute of a freshly built workflow
 * aggregate: by setter, else by field, else not at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregatePropertyWriterTest {

  public static class Aggregate {

    private String id;

    private int amount;

    private double rate;

    private java.util.UUID reference;

    private java.time.LocalDate due;

    private Decision decision;

    private java.util.Date signedAt;

    private java.util.TimeZone zone;

    private java.time.Instant seenAt;

    String withoutSetter;

    private final String readOnly = "fixed";

    public void setId(
        final String id) {
      this.id = id;
    }

    public void setAmount(
        final int amount) {
      this.amount = amount;
    }

    public void setRate(
        final double rate) {
      this.rate = rate;
    }

    public void setReference(
        final java.util.UUID reference) {
      this.reference = reference;
    }

    public void setDue(
        final java.time.LocalDate due) {
      this.due = due;
    }

    public void setDecision(
        final Decision decision) {
      this.decision = decision;
    }

    public void setSignedAt(
        final java.util.Date signedAt) {
      this.signedAt = signedAt;
    }

    public void setZone(
        final java.util.TimeZone zone) {
      this.zone = zone;
    }

    public void setSeenAt(
        final java.time.Instant seenAt) {
      this.seenAt = seenAt;
    }

    public String getReadOnly() {
      return readOnly;
    }

  }

  public enum Decision {
    APPROVED,
    REJECTED
  }

  public static class Child extends Aggregate {

  }

  @Test
  @DisplayName("A setter is used, and the value is converted to its parameter type")
  public void setterWins() {

    final var aggregate = new Aggregate();

    assertTrue(AggregatePropertyWriter.write(aggregate, "id", "4711", "the ID"));
    assertEquals("4711", aggregate.id);

    assertTrue(AggregatePropertyWriter.write(aggregate, "amount", "42", "the amount"));
    assertEquals(42, aggregate.amount);

  }

  @Test
  @DisplayName("Without a setter the field is written, including one inherited from a superclass")
  public void fieldIsTheFallback() {

    final var aggregate = new Child();

    assertTrue(AggregatePropertyWriter.write(aggregate, "withoutSetter", "value", "the attribute"));
    assertEquals("value", aggregate.withoutSetter);

  }

  @Test
  @DisplayName("An attribute the aggregate does not have is reported, not forced")
  public void unknownAttributeIsReported() {

    assertFalse(AggregatePropertyWriter.write(new Aggregate(), "notModelled", "value", "the attribute"));

  }

  @Test
  @DisplayName("A value which does not fit the attribute fails naming what was written")
  public void unconvertibleValueFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> AggregatePropertyWriter.write(new Aggregate(), "amount", new Object(), "the amount"));

    assertTrue(exception.getMessage().contains("the amount"));

  }

  @Test
  @DisplayName("A number the attribute cannot hold fails instead of being cut down to size")
  public void aNumberTheAttributeCannotHoldFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> AggregatePropertyWriter.write(new Aggregate(), "amount", 3000000000L, "the amount"));

    assertTrue(exception.getMessage().contains("The value '3000000000'"));
    assertTrue(exception.getMessage().contains("which would hold '-1294967296'"));
    assertTrue(exception.getMessage().contains("the amount"));

  }

  @Test
  @DisplayName("A decimal written into an integral attribute fails instead of dropping its fraction")
  public void aDecimalIntoAnIntegralAttributeFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> AggregatePropertyWriter.write(new Aggregate(), "amount", new BigDecimal("120.50"), "the amount"));

    assertTrue(exception.getMessage().contains("The value '120.50'"));
    assertTrue(exception.getMessage().contains("which would hold '120'"));

  }

  @Test
  @DisplayName("A value the BPMS carries as text is written as the type the attribute declares")
  public void textValuesAreWrittenAsTheirType() {

    final var aggregate = new Aggregate();

    assertTrue(
        AggregatePropertyWriter
            .write(aggregate, "reference", "f81d4fae-7dec-11d0-a765-00a0c91e6bf6", "the reference"));
    assertEquals(java.util.UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6"), aggregate.reference);

    assertTrue(AggregatePropertyWriter.write(aggregate, "due", "2026-09-16", "the due date"));
    assertEquals(java.time.LocalDate.parse("2026-09-16"), aggregate.due);

    assertTrue(AggregatePropertyWriter.write(aggregate, "decision", "APPROVED", "the decision"));
    assertEquals(Decision.APPROVED, aggregate.decision);

  }

  @Test
  @DisplayName("A constant name the model invented does not reach the aggregate")
  public void anInventedEnumConstantIsNotWritten() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> AggregatePropertyWriter.write(new Aggregate(), "decision", "ESCALATED", "the decision"));

    assertTrue(exception.getMessage().contains("'ESCALATED'"));
    assertTrue(exception.getMessage().contains("'APPROVED', 'REJECTED'"));
    assertTrue(exception.getMessage().contains("the decision"));

  }

  @Test
  @DisplayName("A point in time and a zone reach the aggregate as the types it declares")
  public void aPointInTimeAndAZoneAreWritten() {

    final var aggregate = new Aggregate();

    assertTrue(AggregatePropertyWriter.write(aggregate, "signedAt", "2026-09-16T19:55:30.123Z", "the signing date"));
    assertEquals(java.util.Date.from(java.time.Instant.parse("2026-09-16T19:55:30.123Z")), aggregate.signedAt,
        "the milliseconds survive");

    assertTrue(AggregatePropertyWriter.write(aggregate, "seenAt", "2026-09-16T19:55:30.123Z", "the sighting"));
    assertEquals(java.time.Instant.parse("2026-09-16T19:55:30.123Z"), aggregate.seenAt);

    assertTrue(AggregatePropertyWriter.write(aggregate, "zone", "Europe/Berlin", "the zone"));
    assertEquals(java.util.TimeZone.getTimeZone("Europe/Berlin"), aggregate.zone);

  }

  @Test
  @DisplayName("A zone the model invented does not reach the aggregate as GMT")
  public void aZoneNobodyKnowsIsRefused() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> AggregatePropertyWriter.write(new Aggregate(), "zone", "Nonsense/Zone", "the zone"));

    assertTrue(exception.getMessage().contains("'Nonsense/Zone'"));
    assertTrue(exception.getMessage().contains("'Europe/Berlin'"));
    assertTrue(exception.getMessage().contains("the zone"));

  }

  @Test
  @DisplayName("A scale the attribute cannot keep is no loss, so the value is written")
  public void aScaleTheAttributeCannotKeepIsNoLoss() {

    final var aggregate = new Aggregate();

    assertTrue(AggregatePropertyWriter.write(aggregate, "rate", new BigDecimal("120.50"), "the rate"));
    assertEquals(120.5d, aggregate.rate);

  }

}
