package io.vanillabp.integration.deployment.config;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Says that the migration adapter properties are built and published as a bean. A build
 * step which generates beans injecting them asks for this item so that it runs after the
 * step producing it.
 * <p>
 * The item carries no value. What the producing step holds is a
 * {@link io.quarkus.runtime.RuntimeValue}, which exists only once the application starts,
 * so no build step could read the properties from here anyway.
 */
public final class MigrationAdapterPropertiesBuildItem extends SimpleBuildItem {

  /**
   * Built by {@link ConfigBuildStepProcessor#buildMigrationAdapterProperties}, the step
   * which records how the properties are built and judged when the application starts.
   */
  public MigrationAdapterPropertiesBuildItem() {
  }

}
