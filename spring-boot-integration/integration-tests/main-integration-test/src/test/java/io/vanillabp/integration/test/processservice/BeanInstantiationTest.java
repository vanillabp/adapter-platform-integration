package io.vanillabp.integration.test.processservice;

import io.vanillabp.integration.test.sample2.Aggregate;
import io.vanillabp.integration.test.sample2.SampleWorkflowService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.adapters.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

@SpringBootTest(
    classes = {
        DummyAdapterConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, JpaSpringDataUtilConfiguration.class, io.vanillabp.integration.test.sample.SampleWorkflowService.class, SampleWorkflowService.class, WorkflowModuleConfiguration.class
    }
)
@ExtendWith(SuppressOutputExtension.class)
public class BeanInstantiationTest {

  @Autowired
  private io.vanillabp.integration.test.sample.SampleWorkflowService sampleWorkflowService;

  @Autowired
  private ProcessService<io.vanillabp.integration.test.sample.Aggregate> sampleProcessService;

  @Autowired
  private SampleWorkflowService sampleWorkflowService2;

  @Autowired
  private ProcessService<Aggregate> sampleProcessService2;

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
