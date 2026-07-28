package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Same-config-same-outcome matrix (core validation on all platforms): a
 * module-adapter entry referencing an unconfigured adapter id is never used and
 * rejected with a guiding message (V1-style check).
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnusedModuleAdapterEntryConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("unused-module-adapter/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class, """
          These properties refer to adapter ids not configured in 'vanillabp.adapters.*' - they are never used:
            vanillabp.workflow-modules.test-module.adapters.typo-adapter
          Configured adapter ids are: 'test'. Fix the adapter id or add a section 'vanillabp.adapters.<id>'."""));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to the expected startup exception
  }

}
