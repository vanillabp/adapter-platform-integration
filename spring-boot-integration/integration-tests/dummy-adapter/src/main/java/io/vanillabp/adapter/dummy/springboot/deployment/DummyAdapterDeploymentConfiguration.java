package io.vanillabp.adapter.dummy.springboot.deployment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;

@AutoConfiguration(after = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyAdapterDeploymentConfiguration {

  @Bean
  public List<AdapterDeploymentService<Object, Object>> dummyDeploymentServices(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties) {

    final List<AdapterDeploymentService<Object, Object>> deploymentServices = new ArrayList<>();
    final Set<String> adaptersBuilt = new HashSet<>();

    // walk through all workflow modules
    allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        // for each adapter configured...
        .forEach(workflowModuleId -> properties
            .getPrioritizedAdaptersFor(workflowModuleId)
            .stream()
            // ...find adapters of the dummy type...
            .filter(adapterId -> properties
                .getAdapters()
                .get(adapterId)
                .equals(DummyAdapterConfiguration.ADAPTER_TYPE))
            .forEach(adapterId -> {

              // avoid building the same adapter more than once
              if (adaptersBuilt.contains(adapterId)) {
                return;
              }

              deploymentServices.add(new DeploymentService(adapterId));
              adaptersBuilt.add(adapterId);

            }));

    return deploymentServices;

  }

}
