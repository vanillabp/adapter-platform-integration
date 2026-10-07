package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where a QUARKUS application may write <code>implemented-externally</code>. The rule and the
 * order of the eight positions are the core's ({@code ImplementedExternallyTest}); what this asks
 * is whether every position survives the config mapping and the mapper onto the core model, and
 * whether the line a message prints for a job type with a colon binds as it is printed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusImplementedExternallyLevelsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String TASK = "Activity_Review";

  private static final String ADAPTER = "demo1";

  private static final String WORKFLOW_KEY = "vanillabp.workflow-modules."
      + MODULE
      + ".workflows."
      + PROCESS;

  private static MigrationAdapterProperties configured(
      final Map<String, String> properties) {

    final var withTheAdapter = new HashMap<>(properties);
    withTheAdapter.put("vanillabp.adapters.%s.type".formatted(ADAPTER), "dummy");
    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .withSources(new PropertiesConfigSource(withTheAdapter, "test", 500))
        .build();
    return QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(config.getConfigMapping(QuarkusMigrationAdapterProperties.class));

  }

  @Test
  @DisplayName("Each of the eight positions arrives in the core")
  public void everyPositionArrives() {

    final var positions = List
        .of(
            "vanillabp.implemented-externally",
            "vanillabp.adapters.%s.implemented-externally".formatted(ADAPTER),
            "vanillabp.workflow-modules.%s.implemented-externally".formatted(MODULE),
            "vanillabp.workflow-modules.%s.adapters.%s.implemented-externally".formatted(MODULE, ADAPTER),
            WORKFLOW_KEY
                + ".implemented-externally",
            WORKFLOW_KEY + ".adapters.%s.implemented-externally".formatted(ADAPTER),
            WORKFLOW_KEY + ".tasks.%s.implemented-externally".formatted(TASK),
            WORKFLOW_KEY + ".tasks.%s.adapters.%s.implemented-externally".formatted(TASK, ADAPTER));
    for (final var position : positions) {
      assertEquals(
          Boolean.TRUE,
          configured(Map.of(position, "true")).implementedExternally(MODULE, PROCESS, List.of(TASK), ADAPTER),
          () -> "the position "
              + position);
    }

  }

  @Test
  @DisplayName("Nothing written is nothing marked")
  public void nothingWrittenIsNothingMarked() {

    assertNull(configured(Map.of()).implementedExternally(MODULE, PROCESS, List.of(TASK), ADAPTER));

  }

  @Test
  @DisplayName("The line a message hands over for a job type with a colon binds as it is printed")
  public void theProtectedLineBinds() throws IOException {

    final var printed = ImplementedExternally.propertyLine(MODULE, PROCESS, "io.camunda:http-json:1");
    final var quarkusLine = printed
        .lines()
        .dropWhile(line -> !line.equals("# Quarkus"))
        .skip(1)
        .findFirst()
        .orElseThrow();
    final var fromTheFile = new Properties();
    fromTheFile.load(new StringReader(quarkusLine));
    final var asMap = new HashMap<String, String>();
    fromTheFile.forEach((
        key,
        value) -> asMap.put((String) key, (String) value));

    assertEquals(
        Boolean.TRUE,
        configured(asMap).implementedExternally(MODULE, PROCESS, List.of("io.camunda:http-json:1"), ADAPTER),
        () -> "the key as printed: "
            + quarkusLine);

  }

}
