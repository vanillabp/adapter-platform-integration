package io.vanillabp.integration.test.adapter;

import java.util.List;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces a {@link DeploymentService} recording calls to
 * {@link DeploymentService#stopWorkflowProcessing(List)} in a system property, so
 * tests can verify the shutdown pass was executed by the Quarkus integration's
 * shutdown observer (system properties are visible across the test's classloaders).
 */
@ApplicationScoped
public class RecordingDeploymentServiceProducer {

  /**
   * The system property receiving the workflow module IDs passed to
   * {@link DeploymentService#stopWorkflowProcessing(List)}.
   */
  // deliberately OUTSIDE the vanillabp.* tree: with the blanket withMappingIgnore
  // gone, unknown keys under vanillabp.* fail the startup (typo detection)
  public static final String PROPERTY_STOPPED_MODULES = "vanillabp-test.stopped-modules";

  @Produces
  @Singleton
  @Unremovable
  public DeploymentService recordingDeploymentService(
      final MigrationAdapterProperties properties) {

    return new DeploymentService(properties, List.of(), List.of()) {
      @Override
      public <BPMN, PC> void stopWorkflowProcessing(
          final List<String> workflowModuleIds) {

        System.setProperty(PROPERTY_STOPPED_MODULES, String.join(",", workflowModuleIds));
        super.stopWorkflowProcessing(workflowModuleIds);

      }
    };

  }

}
