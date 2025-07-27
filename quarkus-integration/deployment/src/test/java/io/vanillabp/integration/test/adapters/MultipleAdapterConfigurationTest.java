package io.vanillabp.integration.test.adapters;


import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

public class MultipleAdapterConfigurationTest {

  // Start unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("multiple-adapters/application.yaml", "application.yaml")
          .addClass(DummyAdapters.class))                           // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()); // add mocked adapter

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  /**
   * ProcessService<Aggregate> should be created using dummy adapter configured in application.yaml
   */
  @Test
  public void testAdapterConfiguration() {

    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertInstanceOf(MigrationProcessService.class, sampleProcessService);

    final var migrationProcessService = (MigrationProcessService<Aggregate>) sampleProcessService;
    final var workflowAggregateClass = migrationProcessService.getWorkflowAggregateClass();
    Assertions.assertNotNull(workflowAggregateClass);
    Assertions.assertEquals(Aggregate.class, workflowAggregateClass);

    final var adaptersConfigured = migrationProcessService.getAdapters();
    Assertions.assertNotNull(adaptersConfigured);
    Assertions.assertEquals(2, adaptersConfigured.size());

    final var prioritizedAdapters = migrationProcessService.getPrioritizedAdapters();
    Assertions.assertNotNull(prioritizedAdapters);
    Assertions.assertEquals(2, prioritizedAdapters.size());
    final var defaultAdapter = prioritizedAdapters.getFirst();
    Assertions.assertNotNull(defaultAdapter);
    Assertions.assertEquals("test2", defaultAdapter);
    final var backupAdapter = prioritizedAdapters.getLast();
    Assertions.assertNotNull(backupAdapter);
    Assertions.assertEquals("test", backupAdapter);

  }

}
