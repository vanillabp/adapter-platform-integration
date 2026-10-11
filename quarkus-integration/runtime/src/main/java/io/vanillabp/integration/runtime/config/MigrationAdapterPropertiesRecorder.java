package io.vanillabp.integration.runtime.config;

import java.util.Collection;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;

/**
 * Builds a {@link MigrationAdapterProperties} runtime object and validates the properties provided by the application.
 */
@Recorder
public class MigrationAdapterPropertiesRecorder {

  /**
   * Quarkus creates it while it records the build steps. A recorder keeps no state. It
   * carries what the build found out into the starting application. Quarkus decides when its
   * methods run.
   */
  public MigrationAdapterPropertiesRecorder() {
  }

  /**
   * Supplier for the {@link MigrationAdapterProperties} runtime object.
   *
   * @param capabilities The capabilities of all extensions of the project
   * @param workflowModulesFound Information about all workflow modules found in the project
   * @param adaptersFound Names of all adapters found in the project's classpath
   * @return The {@link MigrationAdapterProperties} object
   */
  public RuntimeValue<MigrationAdapterProperties> recordMigrationProperties(
      final Collection<String> capabilities,
      final Collection<WorkflowModule> workflowModulesFound,
      final Collection<String> adaptersFound) {

    final var config = (SmallRyeConfig) ConfigProvider.getConfig();
    final var properties = config.getConfigMapping(QuarkusMigrationAdapterProperties.class);

    // validate properties of adapters against adapters found in the classpath
    final var adapterProperties = QuarkusMigrationAdapterTransformer
        .builder()
        .properties(properties)
        .propertyNames(config.getPropertyNames()) // used to detect properties not mapped (yet)
        .capabilities(capabilities)
        .build()
        .getAndValidatePropertiesConfigured(
            workflowModulesFound,
            adaptersFound);

    return new RuntimeValue<>(adapterProperties);

  }

}
