package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterTransformer;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class NoWorkflowModuleConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("no-workflow-module/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              Unconfigured VanillaBP workflow modules were found in classpath:
                test-module
              Add property keys 'vanillabp.workflow-modules.*' to configure them.
              """ + QuarkusMigrationAdapterTransformer.QUARKUS_EMPTY_SECTION_NOTE));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to the expected build exception
  }

}
