package io.vanillabp.integration.runtime.deployment;

import java.util.List;

import io.quarkus.runtime.ShutdownEvent;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Stops workflow processing on graceful shutdown of the application: the core
 * {@link DeploymentService} notifies extensions and adapters in reverse start order
 * (see {@link DeploymentService#stopWorkflowProcessing(List)}), afterwards all
 * process services are stopped. This is the Quarkus counterpart of the Spring Boot
 * integration's <code>SmartLifecycle.stop()</code>.
 * <p>
 * <b>Semantic difference to Spring Boot (documented, accepted):</b> Spring stops
 * workflow processing <i>before</i> the web server stops serving (lifecycle phase
 * ordering), whereas the Quarkus {@link ShutdownEvent} fires after the HTTP server
 * stopped accepting requests.
 * <p>
 * The actual pipeline state (built contexts) lives in the
 * {@link VanillaBpDeploymentRunner}, so shutdown operates on exactly the pipeline
 * that was started at boot.
 */
@ApplicationScoped
public class VanillaBpShutdownObserver {

  @Inject
  VanillaBpDeploymentRunner deploymentRunner;

  /**
   * Stops workflow processing of all workflow modules on shutdown.
   *
   * @param event The Quarkus shutdown event
   */
  public void onShutdown(
      @Observes final ShutdownEvent event) {

    deploymentRunner.stop();

  }

}
