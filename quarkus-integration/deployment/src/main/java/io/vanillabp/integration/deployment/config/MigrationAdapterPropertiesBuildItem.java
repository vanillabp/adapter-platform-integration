package io.vanillabp.integration.deployment.config;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Says that the migration adapter properties are built and published as a bean. A build
 * step generates beans which inject them. It asks for this item, so it runs after the step
 * which produces it.
 * <p>
 * The item carries no value. The producing step holds a
 * {@link io.quarkus.runtime.RuntimeValue}, which only exists once the application starts.
 * So no build step could read the properties from here anyway.
 */
public final class MigrationAdapterPropertiesBuildItem extends SimpleBuildItem {

  /**
   * Built by {@link ConfigBuildStepProcessor#buildMigrationAdapterProperties}. That step
   * records how the properties are built and checked when the application starts.
   */
  public MigrationAdapterPropertiesBuildItem() {
  }

}
