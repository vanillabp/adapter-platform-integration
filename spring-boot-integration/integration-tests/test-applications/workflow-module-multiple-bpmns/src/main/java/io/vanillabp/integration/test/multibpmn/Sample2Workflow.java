package io.vanillabp.integration.test.multibpmn;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

@Service
@WorkflowService(workflowAggregateClass = Aggregate2.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Sample2Workflow"))
@SuppressWarnings("unused")
public class Sample2Workflow {

  @Autowired
  private ProcessService<Aggregate1> processService;

  public Class<?> getAggregateClass() {
    return Aggregate2.class;
  }

  @WorkflowTask
  public void juhu() {
    LoggerFactory.getLogger(Sample2Workflow.class).info("Juhu: {}", processService);
  }

  public void juhu2() {
    LoggerFactory.getLogger(Sample2Workflow.class).info("Juhu2: {}", processService);
  }

}
