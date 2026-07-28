package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Configuration for a workflow module NOT available in the classpath only yields a
 * WARNING (the core validation is authoritative on all platforms): the properties
 * are never used, but the application still boots - e.g. the module might be added
 * in a later deployment. Formerly the Quarkus transformer failed the build here
 * while Spring Boot warned - the platforms now behave identically.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnknownWorkflowModuleConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("unknown-workflow-module/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                               // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))               // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  @Test
  public void testApplicationBootsDespiteUnknownModuleConfig() {

    // the application booted despite the config for the unknown module 'my-module'
    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertInstanceOf(ProcessServiceBaseCdiBean.class, sampleProcessService);
    Assertions.assertEquals("test-module", sampleProcessService.getWorkflowModuleId());

  }

}
