package io.vanillabp.integration.test.processservice;

import java.time.Duration;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentService;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer;
import io.vanillabp.integration.test.adapter.TestMigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The election cache's bounds are configurable and reach the in-memory
 * default. This application does NOT use the Micrometer extension, which is the other
 * half of the case: it boots exactly as it would without the cache and publishes no meters.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCacheConfigurationTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("workflow-adapter-cache/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  MigrationAdapterProperties properties;

  @Inject
  WorkflowAdapterCache cache;

  @Inject
  WorkflowAdapterCacheStatistics statistics;

  @Test
  @DisplayName("The configured bounds reach the in-memory cache")
  public void configuredBoundsReachTheCache() {

    Assertions.assertEquals(2, properties.getWorkflowAdapterCache().getMaxEntries());
    Assertions.assertEquals(
        Duration.ofMinutes(30),
        properties.getWorkflowAdapterCache().getTimeToLive());

    Assertions.assertInstanceOf(InMemoryWorkflowAdapterCache.class, cache);

    cache.put(MODULE, PROCESS, "1", "test");
    cache.put(MODULE, PROCESS, "2", "test");
    cache.put(MODULE, PROCESS, "3", "test");

    Assertions.assertEquals(
        2,
        statistics.getSize().orElseThrow(),
        "the configured bound has to be the cache's bound");
    Assertions.assertEquals(1, statistics.getEvictions());

  }

  @Test
  @DisplayName("Without the Micrometer extension no meters are published")
  public void noMetersWithoutMicrometer() {

    // the meters themselves cannot even be NAMED here: their class implements
    // Micrometer's MeterBinder, which this application does not have
    final var beansPublishingMeters = io.quarkus.arc.Arc
        .container()
        .beanManager()
        .getBeans(Object.class, jakarta.enterprise.inject.Any.Literal.INSTANCE)
        .stream()
        .filter(bean -> bean
            .getBeanClass()
            .getName()
            .contains("WorkflowAdapterCacheMetrics"))
        .toList();

    Assertions.assertTrue(
        beansPublishingMeters.isEmpty(),
        "metrics are the application's decision - VanillaBP does not bring Micrometer, but got: "
            + beansPublishingMeters);

  }

}
