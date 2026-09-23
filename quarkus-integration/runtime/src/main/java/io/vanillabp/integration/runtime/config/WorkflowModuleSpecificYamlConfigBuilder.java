package io.vanillabp.integration.runtime.config;

import java.util.List;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Abstract config builder class which is a super class for loading
 * yaml formatted properties-files specific to workflow modules. The names of the files
 * are workflow module IDs.
 * <p>
 * The actual class providing workflow module IDs, and the ordinal is
 * generated during augmentation.
 */
public abstract class WorkflowModuleSpecificYamlConfigBuilder implements ConfigBuilder {

  /**
   * Built by Quarkus, through the subclass the build generates: the configuration has
   * to be complete before anything else of the application runs, so the builder cannot
   * wait for the CDI container.
   */
  public WorkflowModuleSpecificYamlConfigBuilder() {
  }

  /**
   * The workflow modules of this application. Which modules there are can only be seen
   * while the application is built, so the generated subclass answers with the list the
   * build found.
   *
   * @return The workflow module IDs for which files should be added
   */
  protected abstract List<String> getWorkflowModuleIds();

  /**
   * Where the values of a module file rank against the other configuration sources. A
   * module file supplies defaults which the application always outranks (see decision 7
   * in the repository's DECISIONS.md), so the ordinal stays below the one of the
   * application's own files. A module's YAML file ranks above its properties file, the
   * same way the application's two files rank.
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
        .map(id -> new WorkflowModuleSpecificYamlConfigSourceProvider(id, getOrdinal()))
        .forEach(provider -> currentBuilder[0] = currentBuilder[0].withSources(provider));
    return currentBuilder[0];

  }

}
