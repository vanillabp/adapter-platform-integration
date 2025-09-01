package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;

public class UnusedAdapterExtensionTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("application.yaml")                     // load sample application properties
          .addAsResource(WorkflowModule.METAINF_WORKFLOWMODULE)        // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.twoDummyAdapters())       // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              VanillaBP Quarkus adapter extensions found but not configured.
              Add adapter specific configuration in properties sections having type set:
                vanillabp.adapters.*.type=dummy2"""));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to the expected build exception
  }

}
