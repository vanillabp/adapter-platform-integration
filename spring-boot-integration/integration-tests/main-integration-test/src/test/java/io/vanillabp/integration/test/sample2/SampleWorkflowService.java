package io.vanillabp.integration.test.sample2;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

@Service("sampleWorkflowService2")
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService {

  @Autowired
  private ProcessService<Aggregate> processService;

  public Class<Aggregate> getAggregateClass() {
    return Aggregate.class;
  }

  @WorkflowTask
  public void juhu() {
    LoggerFactory.getLogger(SampleWorkflowService.class).info("2 Juhu: {}", processService);
  }

  public void juhu2() {
    LoggerFactory.getLogger(SampleWorkflowService.class).info("2 Juhu2: {}", processService);
  }

}
