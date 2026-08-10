package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class UnknownAdapterConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("unknown-adapter/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
                 test of type unknown
              Available adapter types in classpath: [dummy]"""));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to the expected build exception
  }

}
