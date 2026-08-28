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

  /**
   * Collects the adapters' deployment services and the extensions' wiring services
   * via {@link ObjectProvider} streams: the convention is one <i>element</i> bean
   * per adapter/extension (never a bean of type <code>List&lt;...&gt;</code>) so
   * multiple adapter types coexist in one application - the central migration
   * scenario. Since {@link AdapterDeploymentService} extends
   * {@link ExtensionWiringService}, the wiring stream contains the adapters, too;
   * the core {@link DeploymentService} filters them out (adapters are wired
   * explicitly by the deployment pipeline).
   */
  @Bean(BEANNAME_DEPLOYMENTSERVICE)
  public SpringBootDeploymentService deploymentService(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties,
      final ObjectProvider<AdapterDeploymentService<?, ?>> deploymentServiceProvider,
      final ObjectProvider<ExtensionWiringService<?, ?>> wiringServiceProvider,
      final ObjectProvider<ProcessService<?>> processServices,
      final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring) {

    final List<AdapterDeploymentService<?, ?>> deploymentServices = deploymentServiceProvider
        .stream()
        .toList();
    final List<ExtensionWiringService<?, ?>> wiringServices = wiringServiceProvider
        .stream()
        .toList();

    // the wiring interface goes in as well: the two module-level checks nobody has to
    // remember are the core's own duty since story 158
    final var deploymentService = new DeploymentService(
        properties, deploymentServices, wiringServices, workflowTaskWiring);

    return new SpringBootDeploymentService(
        deploymentService, allWorkflowModules, processServices);

  }

}
