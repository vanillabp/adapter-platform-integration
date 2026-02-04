package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.vanillabp.integration.config.SpringBootMigrationAdapterProperties;
import io.vanillabp.integration.config.SpringBootMigrationAdapterTransformer;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SpringBootMigrationAdapterTransformerTest {

  private static SpringBootMigrationAdapterTransformer.SpringBootMigrationAdapterTransformerBuilder transformerBuilder = SpringBootMigrationAdapterTransformer
      .builder();

  private static SpringBootMigrationAdapterProperties.SpringBootMigrationAdapterPropertiesBuilder<?, ?> propsBuilder = SpringBootMigrationAdapterProperties
      .builder();

  @Test
  @Order(0)
  public void testNoAdaptersInClasspath() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> transformerBuilder
            .adaptersFound(List.of())
            .properties(propsBuilder.build())
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals("No adapters found in classpath! Add dependencies providing VanillaBP adapters.",
        exception.getMessage());

  }

  @Test
  @Order(1)
  public void testNoAdaptersConfigured() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> transformerBuilder
            .adaptersFound(List.of("test-adapter"))
            .properties(propsBuilder.build())
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            No adapters configured! Add properties sections for your BPMS (e.g. xxx) having type set to adapters found in classpath:
              vanillabp.adapters.xxx.type=test-adapter""",
        exception.getMessage());

  }

  @Test
  @Order(2)
  public void testUnknownAdaptersConfigured() {

    final var props = propsBuilder
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("unknown")
                .build()))
        .build();

    final var transformer = transformerBuilder
        .adaptersFound(List.of("test-type"))
        .properties(props)
        .build();
    transformerBuilder = transformer.toBuilder(); // save for next test method

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        """
            Properties 'vanillabp.adapters.*.type' must contain VanillaBP adapters available in classpath!
            These adapters are unknown: 'unknown' found in 'vanillabp.adapters.test-adapter.type'.
            Available adapter types currently loaded in classpath: 'test-type'.""",
        exception.getMessage());

  }

  @Test
  @Order(3)
  public void testNoWorkflowModulesConfigured() {

    final var props = propsBuilder
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .build()))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    propsBuilder = props.toBuilder(); // save for next test method

    final var transformer = transformerBuilder
        .properties(props)
        .workflowModulesFound(List.of("test-module"))
        .build();
    transformerBuilder = transformer.toBuilder(); // save for next test method

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        "No workflow-modules configured! Add properties sections 'vanillabp.workflow-modules.test-module'.",
        exception.getMessage());

  }

  @Test
  @Order(4)
  public void testUnconfiguredWorkflowModulesFound() {

    final var props = propsBuilder
        .workflowModules(Map.of(
            "unknown-module",
            SpringBootMigrationAdapterProperties.WorkflowModuleProperties
                .builder()
                .build()))
        .build();

    final var transformer = transformerBuilder
        .properties(props)
        .build();
    transformerBuilder = transformer.toBuilder(); // save for next test method

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        """
            Unconfigured VanillaBP workflow modules were found in classpath:
              test-module
            Add property keys 'vanillabp.workflow-modules.*' to configure them.""",
        exception.getMessage());

  }

  @Test
  @Order(5)
  public void testWorkflowModuleConfigWithoutWorkflowModule() {

    final var props = propsBuilder
        .workflowModules(Map.of(
            "test-module",
            SpringBootMigrationAdapterProperties.WorkflowModuleProperties
                .builder()
                .build(),
            "my-module",
            SpringBootMigrationAdapterProperties.WorkflowModuleProperties
                .builder()
                .build()))
        .build();

    final var transformer = transformerBuilder
        .properties(props)
        .build();
    transformerBuilder = transformer.toBuilder(); // save for next test method

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        """
            Property keys 'vanillabp.workflow-modules.*' must name VanillaBP workflow modules available in classpath!
            These unknown workflow modules were found in properties:
              vanillabp.workflow-modules.my-module
            Available workflow modules currently loaded in classpath: 'test-module'.""",
        exception.getMessage());

  }

  @Test
  @Order(6)
  public void testMissingPrioritizedAdapters() {

    final var props = propsBuilder
        .prioritizedAdapters(List.of("test-adapter"))
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .build(),
            "test2-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .build()))
        .build();

    final var transformer = transformerBuilder
        .adaptersFound(List.of("test-type"))
        .properties(props)
        .build();
    transformerBuilder = transformer.toBuilder(); // save for next test method

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        """
            The property 'vanillabp.prioritized-adapters' must list all the adapters configured in 'vanillabp.adapters.*' to define
            the order in which adapters are addressed to find workflows running.
            Configured adapters are: test2-adapter, test-adapter.""",
        exception.getMessage());

  }

  @Test
  @Order(7)
  public void testMissingAdaptersPrioritized() {

    final var props = propsBuilder
        .prioritizedAdapters(List.of("test2-adapter"))
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .build()))
        .build();

    final var transformer = transformerBuilder
        .adaptersFound(List.of("test-type"))
        .properties(props)
        .build();
    transformerBuilder = transformer.toBuilder(); // save for next test method

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        """
            The property 'vanillabp.prioritized-adapters' lists these adapters for which no property sections were found:
              test2-adapter -> 'vanillabp.adapters.test2-adapter'""",
        exception.getMessage());

  }

}
