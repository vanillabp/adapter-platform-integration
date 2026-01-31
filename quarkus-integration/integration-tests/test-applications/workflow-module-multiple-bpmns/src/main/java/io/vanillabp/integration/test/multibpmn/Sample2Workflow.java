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
@WorkflowService(workflowAggregateClass = Aggregate2.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Sample2Workflow"))
@SuppressWarnings("unused")
public class Sample2Workflow implements AggregatePersistenceAware<Aggregate2> {

  @Inject
  ProcessService<Aggregate1> processService;

  @Override
  public Class<Aggregate2> getAggregateClass() {
    return Aggregate2.class;
  }

  @Override
  public Aggregate2 save(
      final Aggregate2 aggregate) {
    return null; // not necessary for this test
  }

  @Override
  public Object getAggregateId(
      final Aggregate2 aggregate) {
    return null; // not necessary for this test
  }

  @WorkflowTask
  public void juhu() {
    LoggerFactory.getLogger(Sample2Workflow.class).info("Juhu: {}", processService);
  }

  public void juhu2() {
    LoggerFactory.getLogger(Sample2Workflow.class).info("Juhu2: {}", processService);
  }

}
