package io.vanillabp.integration.runtime.deployment;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.deployment.SuspendedWorkflowReporting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the core's reporter of a suspended workflow. Adapters see it as
 * {@link io.vanillabp.integration.adapter.spi.SuspendedWorkflowReporter}. The
 * {@link VanillaBpDeploymentRunner} connects it to the deployment service of each start. See
 * decision 134 in the repository's DECISIONS.md.
 */
@ApplicationScoped
public class SuspendedWorkflowReportingProducer {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   */
  public SuspendedWorkflowReportingProducer() {
  }

  /**
   * The one reporter of the application, not connected yet.
   *
   * @return The core's reporter
   */
  @Produces
  @Singleton
  @Unremovable
  public SuspendedWorkflowReporting suspendedWorkflowReporting() {

    return new SuspendedWorkflowReporting();

  }

}
