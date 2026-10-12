package io.vanillabp.integration.runtime.config;

import java.util.List;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Abstract base class of the config builders which load the properties files of workflow modules.
 * The files are named after the workflow module IDs.
 * <p>
 * The build generates the actual subclass during augmentation. It provides the workflow
 * module IDs and the ordinal.
 */
public abstract class WorkflowModuleSpecificPropertiesConfigBuilder implements ConfigBuilder {

  /**
   * Quarkus creates it, through the subclass which the build generates. The configuration
   * has to be complete before anything else of the application runs. So the builder cannot
   * wait for the CDI container.
   */
  public WorkflowModuleSpecificPropertiesConfigBuilder() {
  }

  /**
   * The workflow modules of this application. Which modules there are can only be seen while
   * the application is built. So the generated subclass answers with the list which the build
   * found.
   *
   * @return The workflow module IDs for which files should be added
   */
  protected abstract List<String> getWorkflowModuleIds();

  /**
   * Where the values of a module file rank against the other configuration sources. A module
   * file gives defaults, and the application always wins over them (see decision 7 in the
   * repository's DECISIONS.md). So the ordinal stays below the one of the application's own
   * files.
   *
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
