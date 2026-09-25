package io.vanillabp.migration.test.values;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.values.DeclaredAggregateValues;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The path into a section which has not run yet. A workflow aggregate holds one
 * sub-object per section of a larger process, and until the section runs that object is
 * <code>null</code>, so a declared value below it resolves to nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
@DisplayName("A declared value of an aggregate")
public class DeclaredAggregateValuesTest {

  /**
   * Stands for the aggregate the declarations belong to. Nothing is read off it here: the
   * class is the key the declarations are kept under.
   */
  private static class Order {
  }

  private static Map<String, Object> valuesWithSection(
      final Object section) {

    final Map<String, Object> values = new LinkedHashMap<>();
    values.put("customer", "ACME");
    values.put("shipping", section);
    return values;

  }

  @Test
  @DisplayName("stops the sync where the object holding it is null")
  public void anUnresolvablePathStopsTheSync() {

    final var declarations = new DeclaredAggregateValues();
    declarations.register(Order.class, List.of("shipping.express"));

    final var message = assertThrows(
        IllegalStateException.class,
        () -> declarations.completeOrRefuse(Order.class, valuesWithSection(null)))
        .getMessage();

    assertTrue(message.contains("'shipping.express'"), message);
    assertTrue(message.contains("'shipping' is null"), message);
    assertTrue(message.contains("boolean getter"), message);
    assertTrue(message.contains("shipping.express=<value>"), message);

  }

  @Test
  @DisplayName("is shared as the declared value where one was named")
  public void aDeclaredSubstituteIsShared() {

    final var declarations = new DeclaredAggregateValues();
    declarations.register(Order.class, List.of("shipping.express=false"));

    final var values = valuesWithSection(null);
    assertDoesNotThrow(() -> declarations.completeOrRefuse(Order.class, values));

    assertEquals(Map.of("express", Boolean.FALSE), values.get("shipping"));

  }

  @Test
  @DisplayName("is left alone where the section did run")
  public void aResolvablePathIsUntouched() {

    final var declarations = new DeclaredAggregateValues();
    declarations.register(Order.class, List.of("shipping.express=false"));

    final Map<String, Object> section = new LinkedHashMap<>();
    section.put("express", Boolean.TRUE);
    final var values = valuesWithSection(section);
    assertDoesNotThrow(() -> declarations.completeOrRefuse(Order.class, values));

    assertEquals(Map.of("express", Boolean.TRUE), values.get("shipping"));

  }

  @Test
  @DisplayName("covers one value, and a wildcard entry covers every value below it")
  public void whatADeclarationCovers() {

    final var declarations = new DeclaredAggregateValues();
    declarations.register(Order.class, List.of("amount", "shipping.*"));

    assertTrue(declarations.covers(Order.class, "amount"));
    assertTrue(declarations.covers(Order.class, "shipping.express"));
    assertTrue(declarations.covers(Order.class, "shipping.address.zip"));
    assertTrue(!declarations.covers(Order.class, "customer"));
    assertTrue(!declarations.covers(Order.class, "shipping"));

  }

  @Test
  @DisplayName("is covered by a lone wildcard, which is what a whole aggregate needs")
  public void aLoneWildcardCoversEverything() {

    final var declarations = new DeclaredAggregateValues();
    declarations.register(Order.class, List.of("*"));

    assertTrue(declarations.covers(Order.class, "amount"));
    assertTrue(declarations.covers(Order.class, "shipping.express"));

  }

}
