package io.vanillabp.integration.test.processservice;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * Sizing and observing the election cache on Spring Boot (story 58): the bounds are
 * bound from <code>vanillabp.workflow-adapter-cache.*</code> and validated at
 * startup, the statistics are published as Micrometer meters where the application
 * brings Micrometer, and an application-provided cache still replaces the default
 * while its lookups are counted like every other cache's.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCacheConfigurationTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private ApplicationContextRunner contextRunner() {

    return new ApplicationContextRunner()
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(WorkflowModuleConfiguration.class, TestPersistenceConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class));

  }

  @Test
  @DisplayName("The configured bounds reach the in-memory cache")
  public void configuredBoundsReachTheCache() {

    contextRunner()
        .withPropertyValues(
            "vanillabp.workflow-adapter-cache.max-entries=2",
            "vanillabp.workflow-adapter-cache.time-to-live=30m")
        .run(context -> {

          final var properties = context.getBean(MigrationAdapterProperties.class);
          Assertions.assertEquals(2, properties.getWorkflowAdapterCache().getMaxEntries());
          Assertions.assertEquals(
              Duration.ofMinutes(30),
              properties.getWorkflowAdapterCache().getTimeToLive());

          final var cache = context.getBean(WorkflowAdapterCache.class);
          Assertions.assertInstanceOf(InMemoryWorkflowAdapterCache.class, cache);
          final var statistics = context.getBean(WorkflowAdapterCacheStatistics.class);

          cache.put(MODULE, PROCESS, "1", "test");
          cache.put(MODULE, PROCESS, "2", "test");
          cache.put(MODULE, PROCESS, "3", "test");

          Assertions.assertEquals(
              2,
              statistics.getSize().orElseThrow(),
              "the configured bound has to be the cache's bound");
          Assertions.assertEquals(1, statistics.getEvictions());

        });

  }

  @Test
  @DisplayName("An unconfigured application keeps the defaults")
  public void defaultsApplyWithoutConfiguration() {

    contextRunner()
        .run(context -> {

          final var properties = context.getBean(MigrationAdapterProperties.class);
          Assertions.assertEquals(
              WorkflowAdapterCacheProperties.DEFAULT_MAX_ENTRIES,
              properties.getWorkflowAdapterCache().getMaxEntries());
          Assertions.assertEquals(
              WorkflowAdapterCacheProperties.DEFAULT_TIME_TO_LIVE,
              properties.getWorkflowAdapterCache().getTimeToLive());

        });

  }

  @Test
  @DisplayName("A cache holding no entry fails the startup, naming the property")
  public void invalidBoundFailsTheStartup() {

    contextRunner()
        .withPropertyValues("vanillabp.workflow-adapter-cache.max-entries=0")
        .run(context -> {

          final var failure = context.getStartupFailure();
          Assertions.assertNotNull(failure, "the startup has to fail");
          final var message = messagesOf(failure);
          Assertions.assertTrue(
              message.contains(WorkflowAdapterCacheProperties.MAX_ENTRIES_PROPERTY),
              "the failure has to name the property but got: "
                  + message);

        });

  }

  @Test
  @DisplayName("The statistics are published as Micrometer meters")
  public void statisticsArePublishedAsMeters() {

    contextRunner()
        .run(context -> {

          final var meters = context.getBean(WorkflowAdapterCacheMeters.class);
          final var registry = new SimpleMeterRegistry();
          meters.bindTo(registry);

          final var cache = context.getBean(WorkflowAdapterCache.class);
          final var processService = processServiceOf(context);
          Assertions.assertNotNull(processService, "the process service uses the same statistics");
          cache.put(MODULE, PROCESS, "42", "test");

          Assertions.assertEquals(
              1.0,
              registry.get(WorkflowAdapterCacheStatistics.METER_SIZE).gauge().value());
          Assertions.assertNotNull(
              registry.get(WorkflowAdapterCacheStatistics.METER_HITS).functionCounter());
          Assertions.assertNotNull(
              registry.get(WorkflowAdapterCacheStatistics.METER_LOST_HINTS).functionCounter());

        });

  }

  @Test
  @DisplayName("Without Micrometer the application boots and reports no metrics")
  public void withoutMicrometerNoMeters() {

    contextRunner()
        .withClassLoader(
            new org.springframework.boot.test.context.FilteredClassLoader(MeterRegistry.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "the application has to boot");
          Assertions.assertTrue(
              context
                  .getBeanNamesForType(WorkflowAdapterCacheStatistics.class).length > 0,
              "the numbers are collected in any case - the eviction warning needs them");
          Assertions.assertEquals(
              0,
              countMeterBeans(context),
              "no meter binder without Micrometer");

        });

  }

  @Test
  @DisplayName("An application-provided cache replaces the default and is counted")
  public void applicationProvidedCacheIsCounted() {

    contextRunner()
        .withUserConfiguration(ApplicationCacheConfiguration.class)
        .run(context -> {

          Assertions.assertEquals(
              1,
              context.getBeanNamesForType(WorkflowAdapterCache.class).length,
              "the application's bean replaces VanillaBP's default");

          final var processService = processServiceOf(context);
          final var statistics = context.getBean(WorkflowAdapterCacheStatistics.class);
          final var cache = context.getBean(ApplicationCache.class);

          // the process services wrap the application's cache - the election of an
          // unknown workflow is a miss counted here
          Assertions.assertNotNull(processService);
          org.springframework.transaction.support.TransactionSynchronizationManager
              .setActualTransactionActive(true);
          try {
            Assertions.assertThrows(
                Exception.class,
                () -> processService.completeTask(new Aggregate(), "task-unknown"));
          } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(false);
          }

          Assertions.assertFalse(cache.gets.isEmpty(), "the election consults the application's cache");
          Assertions.assertTrue(statistics.getMisses() > 0, "its lookups are counted like any other");
          Assertions.assertTrue(
              statistics.getSize().isEmpty(),
              "an application's cache reports no size");

        });

  }

  private static int countMeterBeans(
      final org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {

    return (int) java.util.Arrays
        .stream(context.getBeanDefinitionNames())
        .filter(name -> name.toLowerCase().contains("workflowadaptercachemeters"))
        .count();

  }

  @SuppressWarnings("unchecked")
  private static ProcessService<Aggregate> processServiceOf(
      final org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {

    final var names = context.getBeanNamesForType(
        ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class));
    return names.length == 0
        ? null
        : ((ProcessServiceSpringBean<Aggregate>) context.getBean(names[0], ProcessService.class));

  }

  private static String messagesOf(
      final Throwable failure) {

    final var messages = new StringBuilder();
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      messages
          .append(cause.getMessage())
          .append('\n');
    }
    return messages.toString();

  }

  @Configuration(proxyBeanMethods = false)
  static class ApplicationCacheConfiguration {

    @Bean
    ApplicationCache applicationCache() {

      return new ApplicationCache();

    }

    /**
     * A persistence reporting a stable aggregate ID - the election caches per
     * aggregate ID, so an aggregate without one is never cached.
     */
    @Bean
    io.vanillabp.integration.spi.AggregatePersistenceAware<Aggregate> testAggregatePersistence() {

      return new io.vanillabp.integration.spi.AggregatePersistenceAware<>() {

        @Override
        public Class<Aggregate> getAggregateClass() {
          return Aggregate.class;
        }

        @Override
        public Aggregate save(
            final Aggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final Aggregate aggregate) {
          return "4711";
        }

      };

    }

  }

  /**
   * An application-provided election cache (e.g. backed by a cluster-shared cache).
   */
  static class ApplicationCache implements WorkflowAdapterCache {

    final Map<String, String> entries = new ConcurrentHashMap<>();

    final java.util.List<String> gets = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static String key(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);

    }

    @Override
    public Optional<String> get(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      final var cacheKey = key(workflowModuleId, bpmnProcessId, workflowAggregateId);
      gets.add(cacheKey);
      return Optional.ofNullable(entries.get(cacheKey));

    }

    @Override
    public void put(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String adapterId) {

      entries.put(key(workflowModuleId, bpmnProcessId, workflowAggregateId), adapterId);

    }

    @Override
    public void invalidate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      entries.remove(key(workflowModuleId, bpmnProcessId, workflowAggregateId));

    }

  }

}
