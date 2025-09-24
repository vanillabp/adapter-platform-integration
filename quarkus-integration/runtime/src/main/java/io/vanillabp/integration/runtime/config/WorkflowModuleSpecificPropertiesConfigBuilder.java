package io.vanillabp.integration.runtime.config;

import java.util.List;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Abstract config builder class which is a super class for loading
 * properties-files specific to workflow modules. The names of the files
 * are workflow module IDs.
 * <p>
 * The actual class providing workflow module IDs, and the ordinal is
 * generated during augmentation.
 */
public abstract class WorkflowModuleSpecificPropertiesConfigBuilder implements ConfigBuilder {

  /**
   * @return The workflow module IDs for which files should be added
   */
  protected abstract List<String> getWorkflowModuleIds();

  /**
   * @return The ordinal for proper overriding of properties
   */
  protected abstract int getOrdinal();

  /**
   * Adds a config source provider for each workflow module ID.
   *
   * @param builder The config builder
   * @return The extended config builder
   */
  @Override
  public SmallRyeConfigBuilder configBuilder(
      final SmallRyeConfigBuilder builder) {

    final var currentBuilder = new SmallRyeConfigBuilder[]{
        builder
    };
    getWorkflowModuleIds()
        .stream()
        .map(id -> new WorkflowModuleSpecificPropertiesConfigSourceProvider(id, getOrdinal()))
        .forEach(provider -> currentBuilder[0] = currentBuilder[0].withSources(provider));
    return currentBuilder[0];

  }

}
