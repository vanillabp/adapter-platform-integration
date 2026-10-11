package io.vanillabp.integration.runtime.deployment;

import java.util.List;

import io.quarkus.runtime.ShutdownEvent;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Stops the workflow processing on graceful shutdown of the application. The core's
 * {@link DeploymentService} tells extensions and adapters in reverse start order (see
 * {@link DeploymentService#stopWorkflowProcessing(List)}). After that, all process services
 * are stopped. This is the Quarkus counterpart of the Spring Boot integration's
 * <code>SmartLifecycle.stop()</code>.
 * <p>
 * <b>A difference from Spring Boot (documented and accepted):</b> Spring stops the workflow
 * processing <i>before</i> the web server stops serving, because of the order of the
 * lifecycle phases. The Quarkus {@link ShutdownEvent} fires after the HTTP server stopped
 * accepting requests.
 * <p>
 * The state of the pipeline (the built contexts) lives in the
 * {@link VanillaBpDeploymentRunner}. So the shutdown works on exactly the pipeline which was
 * started at boot.
 */
@ApplicationScoped
public class VanillaBpShutdownObserver {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * The observer keeps no state of its own. It asks the {@link VanillaBpDeploymentRunner} to
   * stop what that bean started.
   */
  public VanillaBpShutdownObserver() {
  }

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
