package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

@ExtendWith(SuppressOutputExtension.class)
public class AdapterConfigurationTest {

  /**
   * What a probe is asked about (story 107). Any scope does here: the adapters of this
   * test answer from what the test told them, not from a deployment.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of("test-module", "TestProcess");

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("application.yaml")                   // load sample application properties
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                           // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class) // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())  // add mocked adapter
      // an environment-variable shaped OVERRIDE of the configured adapter id 'test':
      // accepted by the misbinding validation (only variables introducing UNKNOWN
      // ids fail the startup - see EnvironmentVariableMisbindingTest)
      .overrideConfigKey("VANILLABP_ADAPTERS_TEST_TYPE", "dummy");

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  @Inject
  TestMigratableProcessService migratableProcessService;

  /**
   * ProcessService<Aggregate> should be created using dummy adapter configured in application.yaml
   */
  @Test
  public void testAdapterConfiguration() throws Exception {

    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertInstanceOf(ProcessService.class, sampleProcessService);
    Assertions.assertInstanceOf(ProcessServiceBaseCdiBean.class, sampleProcessService);

    final var processServiceBaseBean = ((ProcessServiceBaseCdiBean<Aggregate>) sampleProcessService);

    final var workflowAggregateClass = processServiceBaseBean.getWorkflowAggregateClass();
    Assertions.assertNotNull(workflowAggregateClass);
    Assertions.assertEquals(Aggregate.class, workflowAggregateClass);

    final var migrationProcessService = processServiceBaseBean.getMigrationProcessService();
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

  /**
   * Awareness enum round-trip through the dummy adapter's process service.
   */
  @Test
  public void testAwarenessOfDummyAdapter() {

    Assertions.assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        migratableProcessService.awarenessOfTask(SCOPE, "42", "task-id"));
    Assertions.assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        migratableProcessService.awarenessOfWorkflow(SCOPE, null, "42"));

  }

}
