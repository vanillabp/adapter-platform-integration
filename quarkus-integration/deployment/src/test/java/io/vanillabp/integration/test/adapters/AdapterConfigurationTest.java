package io.vanillabp.integration.test.adapters;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import jakarta.inject.Inject;

public class AdapterConfigurationTest {

  // Start unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.sample")  // load sample application classes
          .addAsResource("application.yaml"));                 // load sample application properties

  @Inject
  SampleWorkflowService sampleWorkflowService;

  /**
   * WorkflowService should be created using dummy adapter configured in application.yaml
   */
  @Test
  public void testAdapterConfiguration() {

    Assertions.assertNotNull(sampleWorkflowService);

  }

}
