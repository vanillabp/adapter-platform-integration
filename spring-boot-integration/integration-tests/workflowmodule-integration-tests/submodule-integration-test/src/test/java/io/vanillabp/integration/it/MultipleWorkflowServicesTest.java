package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.multibpmn.Aggregate1;
import io.vanillabp.integration.test.multibpmn.Aggregate2;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
public class MultipleWorkflowServicesTest {

  @Autowired
  ProcessService<Aggregate> processService;

  @Autowired
  ProcessService<Aggregate1> processService1;

  @Autowired
  ProcessService<Aggregate2> processService2;

  @Test
  public void testProcessServicesBelongToTheRightWorkflowModule() {

    assertEquals("test-module", processService.getWorkflowModuleId());
    assertEquals("multi-bpmn-module", processService1.getWorkflowModuleId());
    assertEquals("multi-bpmn-module", processService2.getWorkflowModuleId());

  }

}