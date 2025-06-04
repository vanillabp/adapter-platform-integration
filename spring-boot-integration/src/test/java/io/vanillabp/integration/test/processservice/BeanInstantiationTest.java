package io.vanillabp.integration.test.processservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.processservice.AutoConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.spi.process.ProcessService;

@SpringBootTest(
    classes = {
        AutoConfiguration.class, SampleWorkflowService.class, io.vanillabp.integration.test.sample2.SampleWorkflowService.class
    }
)
public class BeanInstantiationTest {

  @Autowired
  private SampleWorkflowService sampleWorkflowService;

  @Autowired
  private ProcessService<Aggregate> sampleProcessService;

  @Autowired
  private io.vanillabp.integration.test.sample2.SampleWorkflowService sampleWorkflowService2;

  @Autowired
  private ProcessService<io.vanillabp.integration.test.sample2.Aggregate> sampleProcessService2;

  @Test
  public void testBeanInstantiation() throws Exception {

    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertNotNull(sampleWorkflowService);

    Assertions.assertNotNull(sampleProcessService2);
    Assertions.assertNotNull(sampleWorkflowService2);

    Assertions.assertNotEquals(sampleProcessService, sampleProcessService2);
    Assertions.assertNotEquals(sampleWorkflowService, sampleWorkflowService2);

    Assertions.assertNotEquals(sampleWorkflowService.getAggregateClass(), sampleWorkflowService2.getAggregateClass());

  }

}
