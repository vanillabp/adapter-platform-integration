package io.vanillabp.integration.test.multibpmn;

import org.slf4j.LoggerFactory;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@WorkflowService(workflowAggregateClass = Aggregate1.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Sample1Workflow"))
@SuppressWarnings("unused")
public class Sample1Workflow implements AggregatePersistenceAware<Aggregate1> {

  @Inject
  ProcessService<Aggregate1> processService;

  @Override
  public Class<Aggregate1> getAggregateClass() {
    return Aggregate1.class;
  }

  @Override
  public Aggregate1 save(
      final Aggregate1 aggregate) {
    return null; // not necessary for this test
  }

  @Override
  public Object getAggregateId(
      final Aggregate1 aggregate) {
    return null; // not necessary for this test
  }

  @WorkflowTask
  public void juhu() {
    LoggerFactory.getLogger(Sample1Workflow.class).info("Juhu: {}", processService);
  }

  public void juhu2() {
    LoggerFactory.getLogger(Sample1Workflow.class).info("Juhu2: {}", processService);
  }

}
