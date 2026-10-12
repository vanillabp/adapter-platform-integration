package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Tests that a graceful shutdown of the application calls the adapter's
 * <code>stopWorkflowProcessing</code> for all deployed workflow modules. The VanillaBP
 * Quarkus integration observes the {@link io.quarkus.runtime.ShutdownEvent} and hands it
 * to the deployment runner. A BPMN file below the configured resources-location makes the
 * pipeline deploy and start the module at boot. The {@link TestAdapterDeploymentService}
 * records the shutdown pass in a system property, which all class loaders of the test can
 * see.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ShutdownObserverTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("application.yaml")                   // load sample application properties
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addAsResource("test-bpmn/test.bpmn", "test-module/processes/dummy/test.bpmn") // makes the pipeline deploy + start the module
          .addClass(DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)                           // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class)             // process service of the mocked adapter
          .addClass(TestAdapterDeploymentService.class) // records the shutdown pass
          .addClass(TestAdapterDeploymentServiceProducer.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()) // add mocked adapter
      // the adapter double names the process after its file, and no class of the sample claims
      // it. The next line says that it belongs to somebody else, so the start goes on
      .overrideConfigKey("vanillabp.workflow-modules.test-module.workflows.\"test.bpmn\".implemented-externally",
          "true")
      // the shutdown pass runs when the application is undeployed after all tests
      .setAfterUndeployListener(() -> Assertions.assertEquals(
          "test-module",
          System.getProperty(TestAdapterDeploymentService.PROPERTY_STOPPED_MODULES)));

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  /**
   * While the application is running, the shutdown pass must not have run yet.
   */
  @Test
  public void testApplicationIsRunning() {

    System.clearProperty(TestAdapterDeploymentService.PROPERTY_STOPPED_MODULES);

    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertNull(
        System.getProperty(TestAdapterDeploymentService.PROPERTY_STOPPED_MODULES));

  }

}
