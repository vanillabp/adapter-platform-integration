package io.vanillabp.integration.deployment.config;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ObjectSubstitutionBuildItem;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.WorkflowModuleSpecificConfigBuilderBuildItem;
import io.vanillabp.integration.runtime.config.MigrationAdapterPropertiesRecorder;
import io.vanillabp.intergration.adapter.spi.MigratableProcessService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * VanillaBP extension build step processor, responsible for building {@link MigrationAdapterProperties} beans.
 */
public class ConfigBuildStepProcessor {

  /**
   * Validates properties given and builds the {@link MigrationAdapterProperties} object based on those properties.
   *
   * @param capabilities Capabilities of the projects all extensions available
   * @param processServicesProvidedByAdapters All {@link MigratableProcessService} beans provided by VanillaBP adapter extensions
   * @param configsBuilt The build item for workflow module configurations as a dependency for this build step
   * @param substitutionsProvided All object substitutions as a dependency for this build step
   * @param workflowModulesFound Information about all workflow modules found in the project
   * @param migrationAdapterPropertiesRecorder Recorder for {@link MigrationAdapterProperties} objects
   * @return The {@link MigrationAdapterProperties} object
   */
  @Record(ExecutionTime.STATIC_INIT)
  @BuildStep
  MigrationAdapterPropertiesBuildItem buildMigrationAdapterProperties(
      final Capabilities capabilities,
      final List<VanillaBpMigratableProcessServiceBuildItem> processServicesProvidedByAdapters,
      final WorkflowModuleSpecificConfigBuilderBuildItem configsBuilt,
      final List<ObjectSubstitutionBuildItem> substitutionsProvided,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
      final MigrationAdapterPropertiesRecorder migrationAdapterPropertiesRecorder) {

    final var adapterTypesOfProcessServicesProvidedByAdapters = processServicesProvidedByAdapters
        .stream()
        .map(VanillaBpMigratableProcessServiceBuildItem::getAdapterType)
        .collect(Collectors.toCollection(HashSet::new)); // pass items to a serializable kind of set

    // check for consistent configuration
    final var migrationAdapterProperties = migrationAdapterPropertiesRecorder.recordMigrationProperties(
        new HashSet<>(capabilities.getCapabilities()), // pass items to a serializable kind of set
        new HashSet<>(workflowModulesFound.getWorkflowModules().keySet()), // pass items to a serializable kind of set
        adapterTypesOfProcessServicesProvidedByAdapters);

    // build properties as a CDI bean for injection into generated ProcessService beans
    syntheticBeanBuildItemBuildProducer
        .produce(
            SyntheticBeanBuildItem
                .configure(MigrationAdapterProperties.class)
                .scope(ApplicationScoped.class)
                .runtimeValue(migrationAdapterProperties)
                .setRuntimeInit()
                .done());

    return new MigrationAdapterPropertiesBuildItem(migrationAdapterProperties);

  }

}
