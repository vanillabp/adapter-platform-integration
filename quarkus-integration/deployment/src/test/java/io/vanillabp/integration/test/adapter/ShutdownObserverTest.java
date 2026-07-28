package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Tests that on graceful shutdown of the application the core deployment service's
 * <code>stopWorkflowProcessing</code> is invoked with all workflow modules (driven by
 * the {@link io.quarkus.runtime.ShutdownEvent} observer of the VanillaBP Quarkus
 * integration).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ShutdownObserverTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("application.yaml")                   // load sample application properties
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                           // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class)             // process service of the mocked adapter
          .addClass(RecordingDeploymentServiceProducer.class))      // records the shutdown pass
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()) // add mocked adapter
      // the shutdown pass runs when the application is undeployed after all tests
      .setAfterUndeployListener(() -> Assertions.assertEquals(
          "test-module",
          System.getProperty(RecordingDeploymentServiceProducer.PROPERTY_STOPPED_MODULES)));

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  /**
   * While the application is running, the shutdown pass must not have been executed yet.
   */
  @Test
  public void testApplicationIsRunning() {

    System.clearProperty(RecordingDeploymentServiceProducer.PROPERTY_STOPPED_MODULES);

    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertNull(
        System.getProperty(RecordingDeploymentServiceProducer.PROPERTY_STOPPED_MODULES));

  }

}
