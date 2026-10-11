package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the process-version acceptance test. Three methods serve one
 * BPMN task. The version of the deployed process tells them apart: two version ranges
 * made of numbers, and one which names a version tag.
 */
@ApplicationScoped
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
