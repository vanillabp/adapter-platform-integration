package io.vanillabp.integration.deployment;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.intergration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.intergration.extension.spi.ExtensionWiringService;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP's deployment service.
 */
@Slf4j
@Configuration
@ConditionalOnBean({
    WorkflowModules.class, MigrationAdapterProperties.class
})
@AutoConfigureAfter({
    WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class
})
public class DeploymentAutoConfiguration {

  static final String BEANNAME_DEPLOYMENTSERVICE = "VanillaBpDeploymentService";

  @Bean(BEANNAME_DEPLOYMENTSERVICE)
  public SpringBootDeploymentService deploymentService(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices) {

    final var deploymentService = new DeploymentService(
        properties, deploymentServices, wiringServices);

    return new SpringBootDeploymentService(
        deploymentService, allWorkflowModules);

  }

}
