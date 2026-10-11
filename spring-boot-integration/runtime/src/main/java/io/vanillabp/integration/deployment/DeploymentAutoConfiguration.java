package io.vanillabp.integration.deployment;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.processservice.WorkflowServicesWhichAreNoBeans;
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
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * workflow modules and the adapter configuration were bound already. Nothing in VanillaBP
   * builds it.
   */
  public DeploymentAutoConfiguration() {
  }

  /**
   * Collects the adapters' deployment services and the extensions' wiring services
   * with {@link ObjectProvider} streams. The convention is one <i>element</i> bean per
   * adapter or extension, never a bean of type <code>List&lt;...&gt;</code>. That way
   * several adapter types live side by side in one application, which is the central
   * migration case. {@link AdapterDeploymentService} extends
   * {@link ExtensionWiringService}, so the wiring stream contains the adapters, too. The
   * core {@link DeploymentService} filters them out, because the deployment pipeline
   * wires the adapters on its own.
   *
   * @param allWorkflowModules The workflow modules of this application. Their resources are
   *          deployed, and everything below is done per workflow module id
   * @param properties Everything below <code>vanillabp</code> after Spring bound it. It
   *          says which adapter serves a workflow
   * @param deploymentServiceProvider The adapters' deployment services, one bean per
   *          configured adapter
   * @param wiringServiceProvider The extensions' wiring services, one bean each, the
   *          adapters included for the reason named above
   * @param processServices The process service beans, which are stopped again when workflow
   *          processing stops
   * @param workflowTaskWiring What connects a delivered task to the
   *          <code>&#64;WorkflowTask</code> method serving it
   * @param resourceLoader Reads a workflow module's BPMN and DMN files. It also finds a
   *          class which carries <code>&#64;WorkflowService</code> but is no bean, while a
   *          process which nothing claims is reported
   * @return The lifecycle bean which deploys while the context comes up and stops workflow
   *         processing when it goes down
   */
  @Bean(BEANNAME_DEPLOYMENTSERVICE)
  public SpringBootDeploymentService deploymentService(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties,
      final ObjectProvider<AdapterDeploymentService<?, ?>> deploymentServiceProvider,
      final ObjectProvider<ExtensionWiringService<?, ?>> wiringServiceProvider,
      final ObjectProvider<ProcessService<?>> processServices,
      final WorkflowTaskWiring workflowTaskWiring,
      final ResourceLoader resourceLoader) {

    final List<AdapterDeploymentService<?, ?>> deploymentServices = deploymentServiceProvider
        .stream()
        .toList();
    final List<ExtensionWiringService<?, ?>> wiringServices = wiringServiceProvider
        .stream()
        .toList();

    // the wiring interface goes in as well. What belongs to a workflow module as a whole
    // is the core's duty, not an adapter's: the deployed processes which no
    // @WorkflowService class claims, the versions a BPMS still holds for a process id the
    // application only declares, the @WorkflowTask methods which serve no task at all, and
    // the version tags the annotations name.
    // One more helper goes in for the report about a process which nothing claims. On
    // Spring Boot, a workflow service is found because it is a bean. A class which carries
    // the annotation but is no bean is invisible to the discovery. Only a scan of class
    // resources can name it. That scan runs where such a process is reported, and nowhere
    // else
    final var deploymentService = new DeploymentService(
        properties, deploymentServices, wiringServices, workflowTaskWiring, new WorkflowServicesWhichAreNoBeans(
            resourceLoader, allWorkflowModules));

    return new SpringBootDeploymentService(
        deploymentService, allWorkflowModules, processServices);

  }

}
