package io.vanillabp.integration.adapter.migration.workflowstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    public String getReadOnly() {
      return readOnly;
    }

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

}
