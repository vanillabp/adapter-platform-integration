package io.vanillabp.integration.test.deployment;

import java.util.List;

import io.vanillabp.bpmsdouble.DummyProcessVersionSource;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A "BPMS" which gave the model of this start version 5, a version without a tag. The
 * methods of {@link VersionedWorkflowService} serve versions 1 to 3 and the version tagged
 * 'release-2026', so nothing serves the task of the deployed model.
 */
@ApplicationScoped
public class UncoveredDeployedVersionSource implements DummyProcessVersionSource {

  @Override
  public List<DeployedProcessVersion> versionsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return List
        .of(
            DeployedProcessVersion.of("1", null),
            DeployedProcessVersion.of("2", null),
            DeployedProcessVersion.of("3", null),
            DeployedProcessVersion.of("4", "release-2026"),
            DeployedProcessVersion.of("5", null));

  }

  @Override
  public String deployedVersionOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "VersionedProcess".equals(bpmnProcessId)
        ? "5"
        : null;

  }

}
