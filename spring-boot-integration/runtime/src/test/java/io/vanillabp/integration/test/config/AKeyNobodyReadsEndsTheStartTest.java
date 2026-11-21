package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.config.UnreadKeysCheck;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import lombok.Getter;
import lombok.Setter;

/**
 * A key below <code>vanillabp</code> which nothing reads ends the start on Spring Boot, at every
 * level, and the message says what to write instead. See {@link UnreadKeysCheck}.
 * <p>
 * The adapter of this test is shaped like the Camunda 7 adapter. It reads its tenant at the
 * adapter and at the workflow module, and a job timeout at every level. Its overlay class
 * models nothing else. So a tenant at a task is a key which nobody binds.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AKeyNobodyReadsEndsTheStartTest {

  private static final String TASK = "vanillabp.workflow-modules.test-module.workflows.TestWorkflow.tasks.someTask";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(
          VanillaBpConfigurationBindingTest.ClasspathFactsConfiguration.class, AdapterOverlayConfiguration.class)
      .withConfiguration(AutoConfigurations.of(SpringBootMigrationAdapterAutoConfiguration.class))
      .withPropertyValues("vanillabp.adapters.test.type=dummy");

  /**
   * The overlay class of an adapter which reads a tenant at two levels and a job timeout at
   * all four, like the Camunda 7 adapter does with its tenant.
   */
  @Getter
  @Setter
  @ConfigurationProperties(MigrationAdapterProperties.PREFIX)
  static class AdapterOverlay {

    private Map<String, AdapterKeys> adapters = Map.of();

    private Map<String, ModuleOverlay> workflowModules = Map.of();

  }

  /** What the adapter reads in its own section. */
  @Getter
  @Setter
  static class AdapterKeys extends ModuleKeys {

    private String restAddress;

  }

  /** What the adapter reads at a workflow module. */
  @Getter
  @Setter
  static class ModuleKeys extends ScopedKeys {

    private String tenantId;

  }

  /** What the adapter reads at every level. */
  @Getter
  @Setter
  static class ScopedKeys {

    private Duration jobTimeout;

  }

  /** A workflow module of the overlay. */
  @Getter
  @Setter
  static class ModuleOverlay {

    private Map<String, ModuleKeys> adapters = Map.of();

    private Map<String, WorkflowOverlay> workflows = Map.of();

  }

  /** A workflow of the overlay. */
  @Getter
  @Setter
  static class WorkflowOverlay {

    private Map<String, ScopedKeys> adapters = Map.of();

    private Map<String, TaskOverlay> tasks = Map.of();

  }

  /** A task of the overlay. */
  @Getter
  @Setter
  static class TaskOverlay {

    private Map<String, ScopedKeys> adapters = Map.of();

  }

  @Configuration
  @EnableConfigurationProperties(AdapterOverlay.class)
  static class AdapterOverlayConfiguration {
  }

  /** The message of the failed start, with every cause. */
  private static String startupFailureOf(
      final AssertableApplicationContext context) {

    assertNotNull(context.getStartupFailure(), "the start should end");
    final var message = new StringBuilder();
    for (var cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
      message
          .append(cause.getMessage())
          .append('\n');
    }
    return message.toString();

  }

  private static void assertContains(
      final String expected,
      final String message) {

    assertTrue(message.contains(expected), "expected\n%s\nin\n%s".formatted(expected, message));

  }

  @Test
  @DisplayName("A key the adapter reads at two other levels ends the start at a task and names both levels")
  public void aTenantAtATaskEndsTheStart() {

    contextRunner
        .withPropertyValues("%s.adapters.test.tenant-id=x".formatted(TASK))
        .run(context -> {
          final var message = startupFailureOf(context);
          assertContains("Nothing reads this key below 'vanillabp'.", message);
          assertContains("%s.adapters.test.tenant-id".formatted(TASK), message);
          assertContains("'tenant-id' is not read at a task, for one adapter. It is read here:", message);
          assertContains("\n      vanillabp.adapters.test.tenant-id                                at an adapter\n",
              message);
          assertContains(
              "\n      vanillabp.workflow-modules.test-module.adapters.test.tenant-id   at a workflow module, for one adapter\n",
              message);
          assertContains("Move the line to one of these places, or remove it.", message);
        });

  }

  @Test
  @DisplayName("The message names the file and the line where the key is written")
  public void theMessageNamesTheFileAndTheLine() {

    contextRunner
        .withInitializer(context -> {
          try {
            new YamlPropertySourceLoader()
                .load("unread-keys", new ClassPathResource("unread-keys/application.yaml"))
                .forEach(document -> context
                    .getEnvironment()
                    .getPropertySources()
                    .addLast(document));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        })
        .run(context -> assertContains(
            "%s.adapters.test.tenant-id (class path resource [unread-keys/application.yaml] - 13:30)".formatted(TASK),
            startupFailureOf(context)));

  }

  /**
   * One wrong key per level, and the hint it gets.
   *
   * @return The key and the part of the message which has to be there
   */
  static Stream<Arguments> oneTypoPerLevel() {

    return Stream
        .of(
            Arguments.of(
                "application", "vanillabp.prioritised-adapters=test",
                "There is no key 'prioritised-adapters' at 'vanillabp'. Did you mean 'vanillabp.prioritized-adapters'?"),
            Arguments.of(
                "adapter", "vanillabp.adapters.test.tenat-id=x",
                "There is no key 'tenat-id' at 'vanillabp.adapters.test'. Did you mean 'vanillabp.adapters.test.tenant-id'?"),
            Arguments.of(
                "workflow module", "vanillabp.workflow-modules.test-module.alow-full-sync-with-bpms=true",
                "Did you mean 'vanillabp.workflow-modules.test-module.allow-full-sync-with-bpms'?"),
            Arguments.of(
                "workflow module, for one adapter", "vanillabp.workflow-modules.test-module.adapters.test.tenantid2=x",
                "Did you mean 'vanillabp.workflow-modules.test-module.adapters.test.tenant-id'?"),
            Arguments.of(
                "workflow",
                "vanillabp.workflow-modules.test-module.workflowz.TestWorkflow.adapters.test.job-timeout=PT1M",
                "Did you mean 'vanillabp.workflow-modules.test-module.workflows.TestWorkflow.adapters.test.job-timeout'?"),
            Arguments.of(
                "workflow, for one adapter",
                "vanillabp.workflow-modules.test-module.workflows.TestWorkflow.adapters.test.job-timout=PT1M",
                "Did you mean 'vanillabp.workflow-modules.test-module.workflows.TestWorkflow.adapters.test.job-timeout'?"),
            Arguments.of(
                "task", "%s.implemented-externaly=true".formatted(TASK),
                "Did you mean '%s.implemented-externally'?".formatted(TASK)),
            Arguments.of(
                "task, for one adapter", "%s.adapters.test.job-timout=PT1M".formatted(TASK),
                "Did you mean '%s.adapters.test.job-timeout'?".formatted(TASK)),
            Arguments.of(
                "message",
                "vanillabp.workflow-modules.test-module.workflows.TestWorkflow.messages.Approved.message-time-to-life=PT1M",
                "Did you mean 'vanillabp.workflow-modules.test-module.workflows.TestWorkflow.messages.Approved.message-time-to-live'?"),
            Arguments.of(
                "outbox", "vanillabp.outbox.jdbc.tabel=OUTBOX",
                "Did you mean 'vanillabp.outbox.jdbc.table'?"));

  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("oneTypoPerLevel")
  @DisplayName("A typo ends the start at every level and names the key it most likely is")
  public void aTypoEndsTheStart(
      final String level,
      final String property,
      final String expected) {

    contextRunner
        .withPropertyValues(property)
        .run(context -> {
          final var message = startupFailureOf(context);
          assertContains(property.substring(0, property.indexOf('=')), message);
          assertContains(expected, message);
        });

  }

  @Test
  @DisplayName("Keys every part reads, at every level, start the application")
  public void keysWhichAreReadStartTheApplication() {

    contextRunner
        .withPropertyValues(
            "vanillabp.prioritized-adapters[0]=test",
            "vanillabp.resources-location=classpath*:processes",
            "vanillabp.outbox.jdbc.table=OUTBOX",
            "vanillabp.extensions.sample.any.key.below=read by the extension",
            "vanillabp.adapters.test.tenant-id=t",
            "vanillabp.adapters.test.rest-address=http://localhost",
            "vanillabp.adapters.test.job-timeout=PT1M",
            "vanillabp.adapters.test.extensions.sample.greeting=hello",
            "vanillabp.workflow-modules.test-module.prioritized-adapters=test",
            "vanillabp.workflow-modules.test-module.adapters.test.tenant-id=t",
            "vanillabp.workflow-modules.test-module.workflows.TestWorkflow.adapters.test.job-timeout=PT2M",
            "vanillabp.workflow-modules.test-module.workflows.TestWorkflow.messages.Approved.message-time-to-live=PT1M",
            "%s.adapters.test.job-timeout=PT3M".formatted(TASK),
            "%s.implemented-externally=true".formatted(TASK))
        .run(context -> {
          assertNull(context.getStartupFailure(), "the context should start");
          assertEquals(
              Duration.ofMinutes(3),
              context
                  .getBean(AdapterOverlay.class)
                  .getWorkflowModules()
                  .get("test-module")
                  .getWorkflows()
                  .get("TestWorkflow")
                  .getTasks()
                  .get("someTask")
                  .getAdapters()
                  .get("test")
                  .getJobTimeout());
        });

  }

  @Test
  @DisplayName("A section written as '{}' and the elements of a list a stronger source replaced start the application")
  public void anEmptySectionAndAReplacedListStartTheApplication() {

    contextRunner
        .withPropertyValues(
            "vanillabp.workflow-modules.test-module.adapters.test=",
            "vanillabp.prioritized-adapters[0]=test",
            "vanillabp.prioritized-adapters[1]=other")
        .withInitializer(context -> context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new org.springframework.core.env.MapPropertySource(
                "stronger", Map.of("vanillabp.prioritized-adapters[0]", "test"))))
        .run(context -> assertNull(context.getStartupFailure(), "the context should start"));

  }

  @Test
  @DisplayName("Environment variables count like keys of a file, in both spellings of a dash")
  public void environmentVariables() {

    final var read = Map
        .<String, Object>of(
            "VANILLABP_ADAPTERS_TEST_TENANTID", "t",
            "VANILLABP_ADAPTERS_TEST_REST_ADDRESS", "http://localhost");
    contextRunner
        .withInitializer(context -> context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, read)))
        .run(context -> {
          assertNull(context.getStartupFailure(), "the context should start");
          final var adapter = context
              .getBean(AdapterOverlay.class)
              .getAdapters()
              .get("test");
          assertEquals("t", adapter.getTenantId());
          assertEquals("http://localhost", adapter.getRestAddress());
        });

    contextRunner
        .withInitializer(context -> context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("VANILLABP_ADAPTERS_TEST_TENANTIDD",
                    "t"))))
        .run(context -> {
          final var message = startupFailureOf(context);
          assertContains("vanillabp.adapters.test.tenantidd", message);
          assertContains("written as 'VANILLABP_ADAPTERS_TEST_TENANTIDD'", message);
          assertContains("Did you mean 'vanillabp.adapters.test.tenant-id'?", message);
        });

  }

  @Test
  @DisplayName("The key of version 1 'default-adapter' names its successor")
  public void theDefaultAdapterOfVersionOne() {

    contextRunner
        .withPropertyValues(
            "vanillabp.default-adapter=test", "vanillabp.workflow-modules.test-module.default-adapter=test")
        .run(context -> {
          final var message = startupFailureOf(context);
          assertContains("Nothing reads these 2 keys below 'vanillabp'.", message);
          assertContains("'vanillabp.prioritized-adapters'", message);
          assertContains("'vanillabp.workflow-modules.test-module.prioritized-adapters'", message);
          // sorted, so every boot reads the same
          assertTrue(message.indexOf("vanillabp.default-adapter") < message
              .indexOf("vanillabp.workflow-modules.test-module.default-adapter"));
        });

  }

  @Test
  @DisplayName("A key which is no typo lists the keys read where it went wrong")
  public void aKeyWhichIsNoTypo() {

    contextRunner
        .withPropertyValues("vanillabp.adapters.test.something-else-entirely=x", "vanillabp.resources-location.below=x")
        .run(context -> {
          final var message = startupFailureOf(context);
          assertContains(
              "There is no key 'something-else-entirely' at 'vanillabp.adapters.test'. These keys are read there:",
              message);
          assertContains("rest-address", message);
          assertContains("tenant-id", message);
          assertContains(
              "'vanillabp.resources-location' is a single value, so nothing below it is read.", message);
        });

  }

  @Test
  @DisplayName("Without the overlay class of the adapter, its keys are keys nobody reads")
  public void theKeysOfAnAdapterWhichIsNotThere() {

    new ApplicationContextRunner()
        .withUserConfiguration(VanillaBpConfigurationBindingTest.ClasspathFactsConfiguration.class)
        .withConfiguration(AutoConfigurations.of(SpringBootMigrationAdapterAutoConfiguration.class))
        .withPropertyValues("vanillabp.adapters.test.type=dummy", "vanillabp.adapters.test.tenant-id=x")
        .run(context -> assertContains("vanillabp.adapters.test.tenant-id", startupFailureOf(context)));

  }

  @Test
  @DisplayName("A lazy overlay class is bound before the check, so its keys count as read")
  public void aLazyOverlay() {

    new ApplicationContextRunner()
        .withUserConfiguration(VanillaBpConfigurationBindingTest.ClasspathFactsConfiguration.class)
        .withConfiguration(AutoConfigurations.of(SpringBootMigrationAdapterAutoConfiguration.class))
        .withBean(AdapterOverlay.class, AdapterOverlay::new, definition -> definition.setLazyInit(true))
        .withPropertyValues("vanillabp.adapters.test.type=dummy", "vanillabp.adapters.test.tenant-id=x")
        .run(context -> assertNull(context.getStartupFailure(), "the context should start"));

  }

  @Test
  @DisplayName("Only keys below 'vanillabp' are checked")
  public void otherKeysAreLeftAlone() {

    contextRunner
        .withPropertyValues("vanillabpx.anything=x", "spring.whatever=x", List
            .of("other.vanillabp.key=x")
            .getFirst())
        .run(context -> assertNull(context.getStartupFailure(), "the context should start"));

  }

}
