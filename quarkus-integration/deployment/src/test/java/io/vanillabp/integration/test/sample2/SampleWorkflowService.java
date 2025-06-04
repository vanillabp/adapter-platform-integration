package io.vanillabp.integration.test.sample2;

import org.slf4j.LoggerFactory;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService {

  @Inject
  private ProcessService<Aggregate> processService;

  public Class<?> getAggregateClass() {
    return Aggregate.class;
  }

  @WorkflowTask
  public void juhu() {
    LoggerFactory.getLogger(SampleWorkflowService.class).info("Juhu: {}", processService);
  }

  public void juhu2() {
    LoggerFactory.getLogger(SampleWorkflowService.class).info("Juhu2: {}", processService);
  }

}
