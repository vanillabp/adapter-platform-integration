package io.vanillabp.integration.deployment.config;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A build item holding the migration adapter properties object built for later initialization.
 */
@Getter
@AllArgsConstructor
public final class MigrationAdapterPropertiesBuildItem extends SimpleBuildItem {

  /**
   * The runtime value of the migration adapter properties object.
   */
  private RuntimeValue<MigrationAdapterProperties> properties;

}
