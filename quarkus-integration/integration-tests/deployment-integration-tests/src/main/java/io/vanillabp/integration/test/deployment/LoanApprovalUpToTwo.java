package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The handlers of the older generation of the model. The whole class serves versions 1
 * and 2. Its {@code @BpmnProcess} says this once, so no method has to say it.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ClassVersionedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ClassVersionedProcess", version = "1-2"))
public class LoanApprovalUpToTwo {

  @WorkflowTask(taskDefinition = "versionedTask")
  public void assessRisk(
      final ClassVersionedAggregate aggregate) {

    aggregate.setServedBy("upToTwo");

  }

}
