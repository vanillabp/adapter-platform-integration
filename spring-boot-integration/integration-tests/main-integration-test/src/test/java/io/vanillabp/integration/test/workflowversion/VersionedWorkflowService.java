package io.vanillabp.integration.test.workflowversion;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the acceptance test for process versions. Three methods serve
 * one BPMN task. The version of the deployed process tells them apart. Two of them name
 * ranges made of numbers, and one names a version tag.
 */
@WorkflowService(
    workflowAggregateClass = VersionedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "VersionedProcess"))
public class VersionedWorkflowService {

  @WorkflowTask(taskDefinition = "versionedTask", version = "1-2")
  public void upToTwo(
      final VersionedAggregate aggregate) {

    aggregate.setServedBy("upToTwo");

  }

  @WorkflowTask(taskDefinition = "versionedTask", version = "3")
  public void three(
      final VersionedAggregate aggregate) {

    aggregate.setServedBy("three");

  }

  @WorkflowTask(taskDefinition = "versionedTask", version = "release-2026")
  public void tagged(
      final VersionedAggregate aggregate) {

    aggregate.setServedBy("tagged");

  }

}
