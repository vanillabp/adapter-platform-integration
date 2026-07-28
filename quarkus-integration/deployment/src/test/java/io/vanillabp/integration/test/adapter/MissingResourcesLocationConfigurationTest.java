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
 * Same-config-same-outcome matrix (core validation on all platforms): a workflow
 * module without any resources-location yields the guiding message naming the
 * property keys to add.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MissingResourcesLocationConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("missing-resources-location/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              Neither property 'vanillabp.workflow-modules.test-module.adapters.test.resources-location' for resources specific to the BPMS
              nor property 'vanillabp.resources-location' for VanillaBP resources (not specific to the BPMS) is set!

              If using first option then the location needs to be specific to the adapter in order to avoid future
              problems once you wish to migrate to another adapter. Sample: 'classpath*:/workflow-resources/test'"""));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to the expected startup exception
  }

}
