package io.vanillabp.integration.test.sample;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

@Service
@WorkflowService(workflowAggregateClass = Aggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SampleWorkflow"))
public class SampleWorkflowService {

  @Autowired
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
