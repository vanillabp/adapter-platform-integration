package io.vanillabp.integration.deployment.config;

import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ObjectSubstitutionBuildItem;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.WorkflowModuleSpecificConfigBuilderBuildItem;
import io.vanillabp.integration.runtime.config.MigrationAdapterPropertiesRecorder;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * VanillaBP extension build step processor, responsible for building {@link MigrationAdapterProperties} beans.
 */
public class ConfigBuildStepProcessor {

  /**
   * Quarkus builds this processor while it augments the application and calls the build step
   * below on it. Nothing else builds it, and the step keeps no state in it.
   */
  public ConfigBuildStepProcessor() {
  }

  /**
   * Records the work which builds and validates the {@link MigrationAdapterProperties}
   * object when the application starts, and publishes it as a bean the generated process
   * services inject. The properties are runtime configuration, so a build can prepare the
   * check but not run it.
   *
   * @param capabilities Capabilities of the projects all extensions available
   * @param processServicesProvidedByAdapters All {@link MigratableProcessService} beans provided by VanillaBP adapter extensions
   * @param configsBuilt The build item for workflow module configurations as a dependency for this build step
   * @param substitutionsProvided All object substitutions as a dependency for this build step
   * @param workflowModulesFound Information about all workflow modules found in the project
   * @param syntheticBeanBuildItemBuildProducer Producer used to publish the properties as a bean
   *          the generated process services inject
   * @param migrationAdapterPropertiesRecorder Recorder for {@link MigrationAdapterProperties} objects
   * @return The item a step generating beans which inject the properties waits for
   */
  @Record(ExecutionTime.RUNTIME_INIT)
  @BuildStep
  MigrationAdapterPropertiesBuildItem buildMigrationAdapterProperties(
      final Capabilities capabilities,
      final List<VanillaBpMigratableProcessServiceBuildItem> processServicesProvidedByAdapters,
      final WorkflowModuleSpecificConfigBuilderBuildItem configsBuilt,
      final List<ObjectSubstitutionBuildItem> substitutionsProvided,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
      final MigrationAdapterPropertiesRecorder migrationAdapterPropertiesRecorder) {

    refuseKeysWhichOnlyExistOnSpringBoot();

    final var adapterTypesOfProcessServicesProvidedByAdapters = processServicesProvidedByAdapters
        .stream()
        .map(VanillaBpMigratableProcessServiceBuildItem::getAdapterType)
        .collect(Collectors.toCollection(HashSet::new)); // pass items to a serializable kind of set

    // check for consistent configuration
    final var migrationAdapterProperties = migrationAdapterPropertiesRecorder.recordMigrationProperties(
        new HashSet<>(capabilities.getCapabilities()), // pass items to a serializable kind of set
        new HashSet<>(workflowModulesFound.getWorkflowModules()), // pass items to a serializable kind of set
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

    return new MigrationAdapterPropertiesBuildItem();

  }

  /**
   * The section of the gruelbox store, which Spring Boot builds and Quarkus does not. The
   * name is written out here because the class holding it lives in the Spring Boot module,
   * which this one does not depend on; {@code GruelboxKeyIsRefusedOnQuarkusTest} names the
   * key as well and fails if the two ever say something different.
   */
  private static final String GRUELBOX_SECTION = "vanillabp.outbox.gruelbox.";

  /**
   * Ends the build of an application which configures something Quarkus does not have.
   * <p>
   * Without this, SmallRye answers such a key with <code>SRCFG00050</code> and the name of
   * the key, which is true and tells nobody what to do: the key is not a typo, it is a
   * setting which exists and belongs to the other platform. The check runs while the
   * application is augmented, so it speaks before that validation does.
   *
   * @throws IllegalStateException Naming every key found and what to write instead
   */
  private static void refuseKeysWhichOnlyExistOnSpringBoot() {

    final var keysOfTheOtherPlatform = new TreeSet<String>();
    ConfigProvider
        .getConfig()
        .getPropertyNames()
        .forEach(propertyName -> {
          if (propertyName.startsWith(GRUELBOX_SECTION)) {
            keysOfTheOtherPlatform.add(propertyName);
          }
        });
    if (keysOfTheOtherPlatform.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            These keys configure the gruelbox outbox store, and Quarkus does not build that \
            store:
              %s
            It runs on Spring Boot alone, because it needs the Spring transaction manager \
            gruelbox is written against. Remove the keys and let VanillaBP store the phase-two \
            entries itself: it writes them into the table of 'vanillabp.outbox.jdbc.*' where the \
            application has a data source, and into the collection of 'vanillabp.outbox.mongo.*' \
            where it has MongoDB."""
            .formatted(String.join("\n  ", keysOfTheOtherPlatform)));

  }

}
