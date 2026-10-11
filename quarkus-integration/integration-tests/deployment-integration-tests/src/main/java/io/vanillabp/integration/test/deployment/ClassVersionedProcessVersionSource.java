package io.vanillabp.integration.test.deployment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.bpmsdouble.DummyProcessVersionSource;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The versions which the "BPMS" of the class-level version acceptance test holds. No tags
 * are involved. Here a number says which generation of the model a delivery belongs to.
 */
@ApplicationScoped
public class ClassVersionedProcessVersionSource implements DummyProcessVersionSource {

  private final List<DeployedProcessVersion> versions = new CopyOnWriteArrayList<>(
      List.of(
          DeployedProcessVersion.of("1", null),
          DeployedProcessVersion.of("2", null),
          DeployedProcessVersion.of("3", null)));

  @Override
  public List<DeployedProcessVersion> versionsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return List.copyOf(versions);

  }

}
