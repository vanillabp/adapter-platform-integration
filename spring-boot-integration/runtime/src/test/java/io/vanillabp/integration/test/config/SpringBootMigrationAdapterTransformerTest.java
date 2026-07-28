package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.config.SpringBootMigrationAdapterProperties;
import io.vanillabp.integration.config.SpringBootMigrationAdapterTransformer;

/**
 * Tests the Spring Boot property transformation. The transformer only performs
 * Spring-Boot-specific mapping plus the checks the core cannot perform (adapters
 * found in classpath, workflow-level rejection, deployment-failure value parsing) -
 * everything else is validated by the core
 * ({@code MigrationAdapterProperties#validateProperties}), so the same
 * configuration yields the same validation outcome on all platforms.
 */
public class SpringBootMigrationAdapterTransformerTest {

  private static SpringBootMigrationAdapterProperties.WorkflowModuleProperties validModule() {

    return SpringBootMigrationAdapterProperties.WorkflowModuleProperties
        .builder()
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterProperties
                .builder()
                .resourcesLocation("classpath:test-module/processes/test-adapter")
                .build()))
        .build();

  }

  private static SpringBootMigrationAdapterProperties.AdapterConfiguration adapterOfTestType() {

    return SpringBootMigrationAdapterProperties.AdapterConfiguration
        .builder()
        .type("test-type")
        .build();

  }

  @Test
  public void testNoAdaptersInClasspath() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of())
            .workflowModulesFound(List.of("test-module"))
            .properties(SpringBootMigrationAdapterProperties.builder().build())
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals("No adapters found in classpath! Add dependencies providing VanillaBP adapters.",
        exception.getMessage());

  }

  @Test
  public void testNoAdaptersConfigured() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-adapter"))
            .workflowModulesFound(List.of("test-module"))
            .properties(SpringBootMigrationAdapterProperties.builder().build())
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            No adapters configured! Add properties sections for your BPMS (e.g. xxx) having type set to adapters found in classpath:
              vanillabp.adapters.xxx.type=test-adapter""",
        exception.getMessage());

  }

  @Test
  public void testUnknownAdapterTypeIsRejectedByCore() {

    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("unknown")
                .build()))
        .workflowModules(Map.of("test-module", validModule()))
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
               test-adapter of type unknown
            Available adapter types in classpath: [test-type]""",
        exception.getMessage());

  }

  @Test
  public void testUnconfiguredWorkflowModulesAreRejectedByCore() {

    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", adapterOfTestType()))
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            Unconfigured VanillaBP workflow modules were found in classpath:
              test-module
            Add property keys 'vanillabp.workflow-modules.*' to configure them.""",
        exception.getMessage());

  }

  @Test
  public void testModuleConfigWithoutModuleInClasspathOnlyWarns() {

    // the core warns about configured-but-unknown modules (the app still boots) -
    // the former Spring-only hard failure was removed for platform parity
    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", adapterOfTestType()))
        .workflowModules(Map.of(
            "test-module", validModule(),
            "unknown-module", validModule()))
        .build();

    final var result = SpringBootMigrationAdapterTransformer
        .builder()
        .adaptersFound(List.of("test-type"))
        .workflowModulesFound(List.of("test-module"))
        .properties(props)
        .build()
        .getAndValidatePropertiesConfigured();

    assertTrue(result.getWorkflowModules().containsKey("test-module"));

  }

  @Test
  public void testIncompletePrioritizedAdaptersAreRejectedByCore() {

    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .prioritizedAdapters(List.of("test-adapter"))
        .adapters(Map.of(
            "test-adapter", adapterOfTestType(),
            "test2-adapter", adapterOfTestType()))
        .workflowModules(Map.of("test-module", validModule()))
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            The property 'vanillabp.prioritized-adapters' must list all the adapters configured in 'vanillabp.adapters.*' to define
            the order in which adapters are addressed to find workflows running.
            Configured adapters are: test2-adapter, test-adapter.""",
        exception.getMessage());

  }

  @Test
  public void testUnknownPrioritizedAdapterIsRejectedByCore() {

    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .prioritizedAdapters(List.of("test2-adapter"))
        .adapters(Map.of("test-adapter", adapterOfTestType()))
        .workflowModules(Map.of("test-module", validModule()))
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
              vanillabp.prioritized-adapters => test2-adapter
            """,
        exception.getMessage());

  }

  @Test
  public void testDuplicatePrioritizedAdapterIsRejectedByCore() {

    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .prioritizedAdapters(List.of("test-adapter", "test2-adapter", "test-adapter"))
        .adapters(Map.of(
            "test-adapter", adapterOfTestType(),
            "test2-adapter", adapterOfTestType()))
        .workflowModules(Map.of("test-module", validModule()))
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertTrue(exception.getMessage().contains("more than once"));
    assertTrue(exception.getMessage().contains("test-adapter"));

  }

  @Test
  public void testUnusedModuleAdapterEntryIsRejectedByCore() {

    final var module = SpringBootMigrationAdapterProperties.WorkflowModuleProperties
        .builder()
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterProperties
                .builder()
                .resourcesLocation("classpath:test-module/processes/test-adapter")
                .build(),
            "typo-adapter",
            SpringBootMigrationAdapterProperties.AdapterProperties
                .builder()
                .resourcesLocation("classpath:test-module/processes/typo-adapter")
                .build()))
        .build();
    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", adapterOfTestType()))
        .workflowModules(Map.of("test-module", module))
        .build();

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertTrue(exception.getMessage()
        .contains("vanillabp.workflow-modules.test-module.adapters.typo-adapter"));
    assertTrue(exception.getMessage().contains("never used"));

  }

  @Test
  public void testWorkflowLevelConfigurationIsRejected() {

    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .prioritizedAdapters(List.of("test-adapter"))
        .adapters(Map.of("test-adapter", adapterOfTestType()))
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

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            Workflow-level configuration is not yet supported! Remove these properties:
              vanillabp.workflow-modules.test-module.workflows""",
        exception.getMessage());

  }

  @Test
  public void testDeploymentFailureIsMapped() {

    final var props = SpringBootMigrationAdapterProperties
        .builder()
        .adapters(Map.of(
            "test-adapter",
            SpringBootMigrationAdapterProperties.AdapterConfiguration
                .builder()
                .type("test-type")
                .deploymentFailure("warn")
                .build()))
        .workflowModules(Map.of("test-module", validModule()))
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

  }

  @Test
  public void testInvalidDeploymentFailureIsRejected() {

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

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> SpringBootMigrationAdapterTransformer
            .builder()
            .adaptersFound(List.of("test-type"))
            .workflowModulesFound(List.of("test-module"))
            .properties(props)
            .build()
            .getAndValidatePropertiesConfigured());
    assertEquals(
        """
            Properties 'vanillabp.adapters.*.deployment-failure' must be one of 'fail' or 'warn'!
            These values are invalid:
              'sometimes' found in 'vanillabp.adapters.test-adapter.deployment-failure'""",
        exception.getMessage());

  }

}
