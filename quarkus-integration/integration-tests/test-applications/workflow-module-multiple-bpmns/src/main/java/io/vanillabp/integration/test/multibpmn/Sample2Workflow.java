package io.vanillabp.integration.test.multibpmn;

import org.slf4j.LoggerFactory;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@WorkflowService(workflowAggregateClass = Aggregate2.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Sample2Workflow"))
@SuppressWarnings("unused")
public class Sample2Workflow {

  @Inject
  ProcessService<Aggregate1> processService;

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
