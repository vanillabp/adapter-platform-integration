package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.ResilienceProperties;
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
  public void testWorkflowLevelConfigurationIsRejected() {

    // build independent properties not to interfere with the ordered tests sharing builders
    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .prioritizedAdapters(List.of("test-adapter"))
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .build()))
        .workflowModules(Map.of(
            "test-module",
            SpringBootMigrationAdapterProperties.WorkflowModuleProperties
                .builder()
                .workflows(Map.of(
                    "MyProcess",
                    SpringBootMigrationAdapterProperties.WorkflowProperties
                        .builder()
                        .prioritizedAdapters(List.of("test-adapter"))
                        .build()))
                .build()))
        .build();

    final var transformer = SpringBootMigrationAdapterTransformer
        .builder()
        .adaptersFound(List.of("test-type"))
        .workflowModulesFound(List.of("test-module"))
        .properties(props)
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        """
            Workflow-level configuration is not yet supported! Remove these properties:
              vanillabp.workflow-modules.test-module.workflows""",
        exception.getMessage());

  }

  @Test
  @Order(8)
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

  @Test
  @Order(9)
  public void testResilienceAndDeploymentFailureAreMapped() {

    // build independent properties not to interfere with the ordered tests sharing builders
    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .deploymentFailure("warn")
                .build()))
        .resilience(SpringBootMigrationAdapterProperties.ResilienceProperties
            .builder()
            .maxRetries(5)
            .initialInterval(Duration.ofSeconds(2))
            .multiplier(1.5)
            .timeout(Duration.ofSeconds(10))
            .build())
        .workflowModules(Map.of(
            "test-module",
            SpringBootMigrationAdapterProperties.WorkflowModuleProperties
                .builder()
                .resilience(SpringBootMigrationAdapterProperties.ResilienceProperties
                    .builder()
                    .maxRetries(9)
                    .build())
                .adapters(Map.of(
                    "test-adapter",
                    SpringBootMigrationAdapterProperties.AdapterProperties
                        .builder()
                        .resourcesLocation("classpath:test-module/processes/test-adapter")
                        .build()))
                .build()))
        .build();

    final var result = SpringBootMigrationAdapterTransformer
        .builder()
        .adaptersFound(List.of("test-type"))
        .workflowModulesFound(List.of("test-module"))
        .properties(props)
        .build()
        .getAndValidatePropertiesConfigured();

    // deployment-failure policy is mapped and defaults to FAIL for other adapters
    assertEquals(DeploymentFailurePolicy.WARN, result.getDeploymentFailureFor("test-adapter"));
    assertEquals(DeploymentFailurePolicy.FAIL, result.getDeploymentFailureFor("other-adapter"));

    // global resilience block is mapped
    final var global = result.getResilienceFor(null, null);
    assertEquals(5, global.getMaxRetries());
    assertEquals(Duration.ofSeconds(2), global.getInitialInterval());
    assertEquals(1.5, global.getMultiplier());
    assertEquals(Duration.ofSeconds(10), global.getTimeout());

    // workflow module resilience block overrides the global block as a whole
    final var module = result.getResilienceFor("test-module", null);
    assertEquals(9, module.getMaxRetries());
    assertEquals(ResilienceProperties.DEFAULT_INITIAL_INTERVAL, module.getInitialInterval());

  }

  @Test
  @Order(10)
  public void testInvalidDeploymentFailureIsRejected() {

    // build independent properties not to interfere with the ordered tests sharing builders
    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .deploymentFailure("sometimes")
                .build()))
        .build();

    final var transformer = SpringBootMigrationAdapterTransformer
        .builder()
        .adaptersFound(List.of("test-type"))
        .workflowModulesFound(List.of("test-module"))
        .properties(props)
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        """
            Properties 'vanillabp.adapters.*.deployment-failure' must be one of 'fail' or 'warn'!
            These values are invalid:
              'sometimes' found in 'vanillabp.adapters.test-adapter.deployment-failure'""",
        exception.getMessage());

  }

  @Test
  @Order(11)
  public void testInvalidResilienceIsRejected() {

    // build independent properties not to interfere with the ordered tests sharing builders
    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .build()))
        .resilience(SpringBootMigrationAdapterProperties.ResilienceProperties
            .builder()
            .maxRetries(-1)
            .build())
        .workflowModules(Map.of(
            "test-module",
            SpringBootMigrationAdapterProperties.WorkflowModuleProperties
                .builder()
                .adapters(Map.of(
                    "test-adapter",
                    SpringBootMigrationAdapterProperties.AdapterProperties
                        .builder()
                        .resourcesLocation("classpath:test-module/processes/test-adapter")
                        .build()))
                .build()))
        .build();

    final var transformer = SpringBootMigrationAdapterTransformer
        .builder()
        .adaptersFound(List.of("test-type"))
        .workflowModulesFound(List.of("test-module"))
        .properties(props)
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        transformer::getAndValidatePropertiesConfigured
    );
    assertEquals(
        "Property 'vanillabp.resilience.max-retries' must not be negative but is '-1'!",
        exception.getMessage());

  }

}
