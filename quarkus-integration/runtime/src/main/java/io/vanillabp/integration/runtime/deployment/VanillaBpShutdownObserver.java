package io.vanillabp.integration.runtime.deployment;

import java.util.List;

import io.quarkus.runtime.ShutdownEvent;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Stops workflow processing on graceful shutdown of the application: the core
 * {@link DeploymentService} notifies extensions and adapters in reverse start order
 * (see {@link DeploymentService#stopWorkflowProcessing(List)}). This is the Quarkus
 * counterpart of the Spring Boot integration's <code>SmartLifecycle.stop()</code>.
 * <p>
 * The {@link DeploymentService} is resolved lazily since the deployment pipeline may
 * not be wired in every application (yet) - in this case there is nothing to stop.
 */
@Slf4j
@ApplicationScoped
public class VanillaBpShutdownObserver {

  @Inject
  @Any
  Instance<DeploymentService> deploymentService;

  @Inject
  MigrationAdapterProperties properties;

  /**
   * Stops workflow processing of all workflow modules on shutdown, before the
   * platform's web or messaging infrastructure is stopped.
   *
   * @param event The Quarkus shutdown event
   */
  public void onShutdown(
      @Observes final ShutdownEvent event) {

    if (!deploymentService.isResolvable()) {
      return;
    }

    final var workflowModuleIds = List.copyOf(
        properties
            .getWorkflowModules()
            .keySet());

    log.info("Stopping workflow processing of workflow modules: {}", workflowModuleIds);

    deploymentService
        .get()
        .stopWorkflowProcessing(workflowModuleIds);

  }

}
