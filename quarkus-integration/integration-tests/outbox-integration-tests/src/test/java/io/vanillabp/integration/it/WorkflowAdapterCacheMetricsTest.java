package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.PerAdapterAwarenessSource;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Sizing and observing the election cache on Quarkus: the bounds come
 * from <code>vanillabp.workflow-adapter-cache.*</code>, and an application using the
 * Micrometer extension gets the cache's numbers as meters - Quarkus applies every
 * {@code MeterBinder} bean to its registry, so nothing but the bean is needed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowAdapterCacheMetricsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("cache-metrics.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(PerAdapterAwarenessSource.class)
          .addClass(TestMeterRegistryProducer.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:workflow-adapter-cache-metrics-it;DB_CLOSE_DELAY=-1");

  @Inject
  MigrationAdapterProperties properties;

  @Inject
  WorkflowAdapterCache cache;

  @Inject
  WorkflowAdapterCacheStatistics statistics;

  /**
   * Quarkus' own {@code MeterRegistry} bean is a composite without any backend in
   * this test, and a composite without children reports nothing - this registry is
   * added to it as a child, so the values are readable.
   */
  @Inject
  SimpleMeterRegistry meterRegistry;

  @Test
  @DisplayName("The configured bounds reach the cache and its numbers reach Micrometer")
  public void boundsAndMetersAreInPlace() {

    assertEquals(2, properties.getWorkflowAdapterCache().getMaxEntries());
    assertEquals(Duration.ofMinutes(30), properties.getWorkflowAdapterCache().getTimeToLive());

    cache.put(MODULE, PROCESS, "1", "test");
    cache.put(MODULE, PROCESS, "2", "test");
    assertTrue(cache.get(MODULE, PROCESS, "2").isPresent());
    cache.put(MODULE, PROCESS, "3", "test");

    assertEquals(2, statistics.getSize().orElseThrow(), "the configured bound is the cache's bound");
    assertEquals(1, statistics.getEvictions());

    assertEquals(
        2.0,
        meterRegistry.get(WorkflowAdapterCacheStatistics.METER_SIZE).gauge().value());
    assertEquals(
        1.0,
        meterRegistry.get(WorkflowAdapterCacheStatistics.METER_EVICTIONS).functionCounter().count());
    assertNotNull(
        meterRegistry.get(WorkflowAdapterCacheStatistics.METER_HITS).functionCounter());
    assertNotNull(
        meterRegistry.get(WorkflowAdapterCacheStatistics.METER_MISSES).functionCounter());
    assertNotNull(
        meterRegistry.get(WorkflowAdapterCacheStatistics.METER_EVICTIONS_UNUSED).functionCounter());
    assertNotNull(
        meterRegistry.get(WorkflowAdapterCacheStatistics.METER_LOST_HINTS).functionCounter());

  }

}
