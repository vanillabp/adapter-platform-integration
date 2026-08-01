package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import lombok.Getter;
import lombok.Setter;

/**
 * Tests the direct binding of the user-facing <code>vanillabp.*</code> tree onto the
 * core model ({@link MigrationAdapterProperties}) - the tree is modeled exactly once,
 * in the core. Also covers the Spring-specific parts kept in the platform: the
 * "adapters found in classpath" check, the case-insensitive
 * <code>deployment-failure</code> enum conversion with a guiding failure, the
 * environment-variable misbinding validation and the coexistence of an adapter-owned
 * overlay class bound to the same <code>vanillabp</code> prefix.
 */
@ExtendWith(SuppressOutputExtension.class)
public class VanillaBpConfigurationBindingTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(ClasspathFactsConfiguration.class)
      .withConfiguration(
          AutoConfigurations.of(SpringBootMigrationAdapterAutoConfiguration.class));

  /**
   * Provides the classpath facts (workflow modules, adapters found) which are
   * normally determined by scanning - the binding under test is independent of the
   * scanning.
   */
  @Configuration
  static class ClasspathFactsConfiguration {

    @Bean
    WorkflowModules workflowModules() {
      return new WorkflowModules(List.of(WorkflowModule
          .builder()
          .id("test-module")
          .sourceUri("file:/test")
          .build()));
    }

    @Bean
    AdapterConfigurationBase testAdapterConfiguration() {
      return new AdapterConfigurationBase() {
        @Override
        public String getAdapterType() {
          return "dummy";
        }
      };
    }

  }

  /**
   * An adapter-owned overlay of the shared <code>vanillabp.*</code> tree carrying
   * adapter-specific keys unknown to the core model - the reference shape of the
   * overlay pattern BPMS adapters use for their connection settings.
   */
  @Getter
  @Setter
  @ConfigurationProperties(MigrationAdapterProperties.PREFIX)
  static class TestAdapterOverlayProperties {

    private Map<String, TestAdapterConfig> adapters = Map.of();

    @Getter
    @Setter
    static class TestAdapterConfig {

      private String restAddress;

      private Duration jobTimeout;

    }

  }

  @Configuration
  @EnableConfigurationProperties(TestAdapterOverlayProperties.class)
  static class OverlayConfiguration {
  }

  @Test
  @DisplayName("The user-facing vanillabp.* tree binds directly onto the core model")
  public void bindsUserFacingTreeOntoCoreModel() {

    contextRunner
        .withPropertyValues(
            "vanillabp.resources-location=classpath*:vanillabp-processes",
            "vanillabp.prioritized-adapters=test,test2",
            "vanillabp.adapters.test.type=dummy",
            "vanillabp.adapters.test.deployment-failure=warn",
            "vanillabp.adapters.test2.type=dummy",
            "vanillabp.workflow-modules.test-module.prioritized-adapters=test",
            "vanillabp.workflow-modules.test-module.adapters.test.resources-location=classpath:bpms-specific")
        .run(context -> {

          final var properties = context.getBean(MigrationAdapterProperties.class);

          assertEquals("classpath*:vanillabp-processes", properties.getResourcesLocation());
          assertEquals(List.of("test", "test2"), properties.getPrioritizedAdapters());
          assertEquals(
              Map.of(
                  "test", "dummy",
                  "test2", "dummy"),
              properties.adapterTypes());
          assertEquals(DeploymentFailurePolicy.WARN, properties.getDeploymentFailureFor("test"));
          assertEquals(DeploymentFailurePolicy.FAIL, properties.getDeploymentFailureFor("test2"));
          assertEquals(
              List.of("test"),
              properties.getPrioritizedAdaptersFor("test-module"));
          final var resourcesLocation = properties.getAdapterResourcesLocationFor("test-module", "test");
          assertEquals("classpath:bpms-specific", resourcesLocation.location());
          assertFalse(resourcesLocation.vanillaBpBpmn());

        });

  }

  @Test
  @DisplayName("A single configured adapter defaults vanillabp.prioritized-adapters (core normalize)")
  public void singleAdapterDefaultsPrioritizedAdapters() {

    contextRunner
        .withPropertyValues(
            "vanillabp.resources-location=classpath*:vanillabp-processes",
            "vanillabp.adapters.test.type=dummy",
            "vanillabp.workflow-modules.test-module.prioritized-adapters=test")
        .run(context -> {

          final var properties = context.getBean(MigrationAdapterProperties.class);
          assertEquals(List.of("test"), properties.getPrioritizedAdapters());

        });

  }

  @Test
  @DisplayName("Missing adapters in the classpath fail with the Spring-specific guiding message")
  public void noAdaptersInClasspathIsRejected() {

    new ApplicationContextRunner()
        .withUserConfiguration(NoAdaptersConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(SpringBootMigrationAdapterAutoConfiguration.class))
        .withPropertyValues("vanillabp.adapters.test.type=dummy")
        .run(context -> {

          assertNotNull(context.getStartupFailure());
          assertTrue(rootMessage(context.getStartupFailure())
              .contains("No adapters found in classpath! Add dependencies providing VanillaBP adapters."));

        });

  }

  @Configuration
  static class NoAdaptersConfiguration {

    @Bean
    WorkflowModules workflowModules() {
      return new WorkflowModules(List.of(WorkflowModule
          .builder()
          .id("test-module")
          .sourceUri("file:/test")
          .build()));
    }

  }

  @Test
  @DisplayName("An invalid deployment-failure value fails naming the key and the allowed values")
  public void invalidDeploymentFailureIsRejected() {

    contextRunner
        .withPropertyValues(
            "vanillabp.resources-location=classpath*:vanillabp-processes",
            "vanillabp.adapters.test.type=dummy",
            "vanillabp.adapters.test.deployment-failure=sometimes")
        .run(context -> {

          assertNotNull(context.getStartupFailure());
          final var message = fullFailureText(context.getStartupFailure());
          assertTrue(message.contains("vanillabp.adapters.test.deployment-failure"));
          assertTrue(message.contains("must be one of 'fail' or 'warn'"));

        });

  }

  @Test
  @DisplayName("Workflow-level configuration binds and is resolved (formerly rejected)")
  public void workflowLevelConfigurationIsAccepted() {

    // regression for the former "not yet supported" rejection (story 27)
    contextRunner
        .withPropertyValues(
            "vanillabp.resources-location=classpath*:vanillabp-processes",
            "vanillabp.prioritized-adapters=test,test2",
            "vanillabp.adapters.test.type=dummy",
            "vanillabp.adapters.test2.type=dummy",
            "vanillabp.workflow-modules.test-module.prioritized-adapters=test,test2",
            "vanillabp.workflow-modules.test-module.workflows.MyProcess.prioritized-adapters=test2,test",
            "vanillabp.workflow-modules.test-module.workflows.MyProcess.adapters.test2.resources-location=classpath:wf-specific")
        .run(context -> {

          assertNotNull(context.getBean(MigrationAdapterProperties.class));
          final var properties = context.getBean(MigrationAdapterProperties.class);

          // the workflow-level prioritized-adapters override wins for MyProcess...
          assertEquals(
              List.of("test2", "test"),
              properties.getPrioritizedAdaptersFor("test-module", "MyProcess"));
          // ...while other workflows use the module-level list
          assertEquals(
              List.of("test", "test2"),
              properties.getPrioritizedAdaptersFor("test-module", "OtherProcess"));

          // adapter-scoped keys bind at the workflow level and resolve most-specific-wins
          assertEquals(
              "classpath:wf-specific",
              properties.resolveForAdapter(
                  "test-module",
                  "MyProcess",
                  null,
                  "test2",
                  AdapterProperties::getResourcesLocation));

        });

  }

  @Test
  @DisplayName("An environment variable overriding a configured id is accepted")
  public void environmentVariableOverridingConfiguredIdIsAccepted() {

    // the raw (environment-variable shaped) key is visible to the misbinding
    // validation like a variable of the systemEnvironment property source
    contextRunner
        .withPropertyValues(
            "vanillabp.resources-location=classpath*:vanillabp-processes",
            "vanillabp.adapters.test.type=dummy",
            "vanillabp.workflow-modules.test-module.prioritized-adapters=test",
            "VANILLABP_ADAPTERS_TEST_DEPLOYMENT_FAILURE=warn")
        .run(context -> assertNotNull(context.getBean(MigrationAdapterProperties.class)));

  }

  @Test
  @DisplayName("An environment variable introducing an unknown adapter id fails with a guiding message")
  public void environmentVariableIntroducingUnknownIdIsRejected() {

    contextRunner
        .withPropertyValues(
            "vanillabp.resources-location=classpath*:vanillabp-processes",
            "vanillabp.adapters.test.type=dummy",
            "vanillabp.workflow-modules.test-module.prioritized-adapters=test",
            "VANILLABP_ADAPTERS_C8_CLOUD_TYPE=dummy")
        .run(context -> {

          assertNotNull(context.getStartupFailure());
          final var message = rootMessage(context.getStartupFailure());
          assertTrue(message.contains("VANILLABP_ADAPTERS_C8_CLOUD_TYPE"));
          assertTrue(message.contains("'test'"));
          assertTrue(message.contains("cannot introduce a new adapter or workflow module"));

        });

  }

  @Test
  @DisplayName("An adapter-owned overlay of the same prefix coexists with the platform binding")
  public void adapterOverlayCoexistsWithPlatformBinding() {

    contextRunner
        .withUserConfiguration(OverlayConfiguration.class)
        .withPropertyValues(
            "vanillabp.resources-location=classpath*:vanillabp-processes",
            "vanillabp.adapters.test.type=dummy",
            "vanillabp.adapters.test.deployment-failure=warn",
            "vanillabp.workflow-modules.test-module.prioritized-adapters=test",
            // adapter-specific keys unknown to the core model - must not break
            // the platform binding and have to reach the overlay typed
            "vanillabp.adapters.test.rest-address=http://localhost:26500",
            "vanillabp.adapters.test.job-timeout=PT5M")
        .run(context -> {

          final var properties = context.getBean(MigrationAdapterProperties.class);
          assertEquals(Map.of("test", "dummy"), properties.adapterTypes());
          assertEquals(DeploymentFailurePolicy.WARN, properties.getDeploymentFailureFor("test"));

          final var overlay = context.getBean(TestAdapterOverlayProperties.class);
          final var overlayAdapter = overlay.getAdapters().get("test");
          assertNotNull(overlayAdapter);
          assertEquals("http://localhost:26500", overlayAdapter.getRestAddress());
          assertEquals(Duration.ofMinutes(5), overlayAdapter.getJobTimeout());

        });

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();

  }

  private static String fullFailureText(
      final Throwable throwable) {

    final var builder = new StringBuilder();
    for (var cause = throwable; cause != null; cause = cause.getCause()) {
      builder
          .append(cause.getMessage())
          .append('\n');
    }
    return builder.toString();

  }

}
