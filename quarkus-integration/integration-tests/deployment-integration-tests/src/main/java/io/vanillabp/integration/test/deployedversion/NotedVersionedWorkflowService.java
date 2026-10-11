package io.vanillabp.integration.test.deployedversion;

import io.vanillabp.integration.test.deployment.VersionedAggregate;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the test about the elements an extension reports. The task has a
 * method in every version. The note of the test extension serves versions 1 to 4 only.
 * Nothing notes the process itself.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = VersionedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "VersionedProcess"))
public class NotedVersionedWorkflowService {

  @WorkflowTask(taskDefinition = "versionedTask")
  public void everyVersion(
      final VersionedAggregate aggregate) {

    aggregate.setServedBy("everyVersion");

  }

  @DeployedVersionNote(element = "Activity_Versioned", version = "1-4")
  public void noteUpToFour(
      final VersionedAggregate aggregate) {

    aggregate.setServedBy("noteUpToFour");

  }

}
