package io.vanillabp.integration.deployment.config;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import lombok.Getter;

/**
 * A build item holding the migration adapter properties object built for later initialization.
 */
@Getter
public final class MigrationAdapterPropertiesBuildItem extends SimpleBuildItem {

  /**
   * The runtime value of the migration adapter properties object.
   */
  private final RuntimeValue<MigrationAdapterProperties> properties;

  /**
   * Built by {@link ConfigBuildStepProcessor#buildMigrationAdapterProperties}, the step
   * which records how the properties are built and judged when the application starts.
   * {@code ProcessServiceBuildStepProcessor} asks for this item so that it runs after that
   * step: the beans it generates inject the properties, which the same step publishes as a
   * synthetic bean.
   * <p>
   * None of the platform's own steps reads the value. It is carried here for a step which
   * needs the object itself.
   *
   * @param properties The properties as the recorder will have built them when the
   *          application starts - a build step can pass the value on, but not look into it
   */
  public MigrationAdapterPropertiesBuildItem(
      final RuntimeValue<MigrationAdapterProperties> properties) {

    this.properties = properties;

  }

}
