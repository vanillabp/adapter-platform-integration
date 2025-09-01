package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.integration.runtime.processservice.ProcessServiceCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

public class AdapterConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("application.yaml")                   // load sample application properties
          .addAsResource(WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
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
    Assertions.assertInstanceOf(ProcessServiceCdiBean.class, sampleProcessService);

    final var migrationProcessService = ((MigrationProcessService<Aggregate>) sampleProcessService);
    final var workflowAggregateClass = migrationProcessService.getWorkflowAggregateClass();
    Assertions.assertNotNull(workflowAggregateClass);
    Assertions.assertEquals(Aggregate.class, workflowAggregateClass);

    final var adaptersConfigured = migrationProcessService.getAdapters();
    Assertions.assertNotNull(adaptersConfigured);
    Assertions.assertEquals(1, adaptersConfigured.size());

    final var adapter = adaptersConfigured.keySet().iterator().next();
    Assertions.assertNotNull(adapter);
    Assertions.assertEquals("test", adapter);

    final var adapterType = adaptersConfigured.get(adapter);
    Assertions.assertNotNull(adapterType);
    Assertions.assertEquals("dummy", adapterType);

    final var prioritizedAdapters = migrationProcessService.getPrioritizedAdapters();
    Assertions.assertNotNull(prioritizedAdapters);
    Assertions.assertEquals(1, prioritizedAdapters.size());
    final var defaultAdapter = prioritizedAdapters.getFirst();
    Assertions.assertNotNull(defaultAdapter);
    Assertions.assertEquals("test", defaultAdapter);

  }

}
