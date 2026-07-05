package io.vanillabp.integration.deployment;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP's deployment service.
 */
@Slf4j
@AutoConfiguration(after = {
    WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class
})
@ConditionalOnBean({
    WorkflowModules.class, MigrationAdapterProperties.class
})
public class DeploymentAutoConfiguration {

  static final String BEANNAME_DEPLOYMENTSERVICE = "VanillaBpDeploymentService";

  @Bean(BEANNAME_DEPLOYMENTSERVICE)
  public SpringBootDeploymentService deploymentService(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices,
      final ObjectProvider<ProcessService<?>> processServices) {

    final var deploymentService = new DeploymentService(
        properties, deploymentServices, wiringServices);

    return new SpringBootDeploymentService(
        deploymentService, allWorkflowModules, processServices);

  }

}
