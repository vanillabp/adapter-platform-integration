package io.vanillabp.integration.adapter.migration.deployment;

import io.vanillabp.integration.adapter.spi.SuspendedWorkflowReporter;
import io.vanillabp.integration.extension.spi.runtime.SuspendedWorkflowFound;
import lombok.extern.slf4j.Slf4j;

/**
 * The core's {@link SuspendedWorkflowReporter}. Both platform integrations hand this one object
 * to every adapter, and connect it to the {@link DeploymentService} of the start.
 * <p>
 * It is an object of its own because of the order in which things are built. An adapter gets
 * its collaborators when it is built. The deployment service is built after the adapters,
 * because it needs them. So the adapter gets this object, and the deployment service is
 * connected to it later. Before that, nothing runs a workflow, so no report can come.
 * <p>
 * Nothing leaves {@link #found(SuspendedWorkflowFound)}. A report which comes before the
 * connection is dropped, and an exception of an extension is logged in
 * {@link DeploymentService#tellExtensionsAbout(SuspendedWorkflowFound)}. See the decision in the
 * repository's DECISIONS.pending/2074.md.
 */
@Slf4j
public class SuspendedWorkflowReporting implements SuspendedWorkflowReporter {

  private volatile DeploymentService deploymentService;

  /**
   * Built once per application by the platform integration, not connected yet.
   */
  public SuspendedWorkflowReporting() {
  }

  /**
   * Connects the deployment service of this start. From now on reports reach the extensions.
   * A later call replaces the service, which is what a restart in Quarkus dev mode does.
   *
   * @param deploymentService The deployment service which knows the extensions and the
   *          processing contexts of the start
   */
  public void connect(
      final DeploymentService deploymentService) {

    this.deploymentService = deploymentService;

  }

  @Override
  public void found(
      final SuspendedWorkflowFound found) {

    final var connected = deploymentService;
    if (connected == null) {
      log
          .debug(
              "Adapter '{}' found workflow '{}' suspended before the deployment started. No extension is told.",
              found.adapterId(),
              found.workflowId());
      return;
    }
    try {
      connected.tellExtensionsAbout(found);
    } catch (RuntimeException e) {
      // tellExtensionsAbout catches what an extension throws. This is the net for a defect of
      // the core itself, because the adapter has to throw what it would have thrown anyway
      log
          .warn(
              "Could not tell the extensions that adapter '{}' found workflow '{}' suspended.",
              found.adapterId(),
              found.workflowId(),
              e);
    }

  }

}
