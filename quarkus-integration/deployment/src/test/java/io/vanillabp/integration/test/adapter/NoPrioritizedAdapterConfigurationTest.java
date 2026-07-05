package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;

public class NoPrioritizedAdapterConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("multiple-adapters/no-prioritized-adapters/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              The property 'vanillabp.prioritized-adapters' must list all the adapters configured in 'vanillabp.adapters.*' to define
              the order in which adapters are addressed to find workflows running.
              Configured adapters are: test2, test."""));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to the expected build exception
  }

}
