package io.vanillabp.integration.test.processservice;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.adapter.TestMigratableProcessService;
import io.vanillabp.integration.test.samples.sample2.Aggregate;
import io.vanillabp.integration.test.samples.sample2.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

@ExtendWith(SuppressOutputExtension.class)
public class BeanInstantiationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(DummyAdapters.class)                          // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class)           // process service of the mocked adapter
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addPackage("io.vanillabp.integration.test.samples.sample2") // load sample application classes
          .addAsResource("application.yaml")                   // load sample application properties
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))          // define workflow module at global classpath
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()); // add mocked adapter

  @Inject
  io.vanillabp.integration.test.samples.sample.SampleWorkflowService sampleWorkflowService;

  @Inject
  ProcessService<io.vanillabp.integration.test.samples.sample.Aggregate> sampleProcessService;

  @Inject
  SampleWorkflowService sampleWorkflowService2;

  @Inject
  ProcessService<Aggregate> sampleProcessService2;

  @Test
  public void testBeanInstantiation() {

    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertNotNull(sampleWorkflowService);

    Assertions.assertNotNull(sampleProcessService2);
    Assertions.assertNotNull(sampleWorkflowService2);

    Assertions.assertNotEquals(sampleProcessService, sampleProcessService2);
    Assertions.assertNotEquals(sampleWorkflowService, sampleWorkflowService2);

    Assertions.assertNotEquals(sampleWorkflowService.getAggregateClass(), sampleWorkflowService2.getAggregateClass());

  }

}
