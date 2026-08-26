package io.vanillabp.integration.test.workflowversionclass;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The handlers of the newer generation of the same model, wired to the same BPMN task.
 * Two classes for one process are ambiguous unless their ranges are disjoint, which
 * these two are.
 */
@WorkflowService(
    workflowAggregateClass = ClassVersionedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ClassVersionedProcess", version = ">2"))
public class LoanApprovalAfterTwo {

  @WorkflowTask(taskDefinition = "versionedTask")
  public void assessRisk(
      final ClassVersionedAggregate aggregate) {

    aggregate.setServedBy("afterTwo");

  }

}
