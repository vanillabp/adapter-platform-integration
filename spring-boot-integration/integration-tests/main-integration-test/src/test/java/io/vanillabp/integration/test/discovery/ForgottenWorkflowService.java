package io.vanillabp.integration.test.discovery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The support case this fixture stands for: somebody wrote a workflow service and forgot the
 * annotation which makes it a bean. It has no {@code @Service} on purpose, and no test hands
 * it to a {@code SpringApplicationBuilder}. So no bean of it exists in any application here.
 * That is why the discovery cannot see it. Only the report about the process which nothing
 * claims names it.
 */
@WorkflowService(
    workflowAggregateClass = ForgottenAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DummyProcess"))
public class ForgottenWorkflowService {

  @WorkflowTask
  public void processTask(
      final ForgottenAggregate aggregate) {

  }

}
