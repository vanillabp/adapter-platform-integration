package io.vanillabp.integration.runtime.config;

import static java.util.Collections.emptyList;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.source.yaml.YamlConfigSourceLoader;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleConfigFiles;

/**
 * A config source provider which loads the config files named after a workflow module ID. The
 * super class finds the variants per profile.
 *
 * <p>Files are searched at the four places where a workflow module may put them.
 * {@link WorkflowModuleConfigFiles} names these places, and the Spring Boot integration
 * searches them too. So a module packaged as its own Maven or Gradle module can keep its
 * configuration next to its other resources. A module which prefers a {@code config}
 * directory can use that instead.
 */
@StaticInitSafe
public class WorkflowModuleSpecificYamlConfigSourceProvider extends YamlConfigSourceLoader implements ConfigSourceProvider {

  private final String workflowModuleId;

  private final int ordinal;

  /**
   * The config builder which knows the modules of this application creates one per workflow
   * module.
   *
   * @param workflowModuleId The module whose file this provider looks for. The file is
   *          named after the module
   * @param ordinal Where the values of that file rank against the other configuration
   *          sources
   */
  public WorkflowModuleSpecificYamlConfigSourceProvider(
      final String workflowModuleId,
      final int ordinal) {

    this.workflowModuleId = workflowModuleId;
    this.ordinal = ordinal;

  }

  /**
   * Finds all config sources for all known file extensions, at each of the four places where
   * a workflow module may put a file.
   *
   * @param classLoader
   *            the class loader, which should be used for discovery and resource loading purposes
   * @return The config sources
   */
  @Override
  public List<ConfigSource> getConfigSources(
      final ClassLoader classLoader) {

    return Arrays
        .stream(getFileExtensions())
        .flatMap(extension -> {
          final var filename = "%s.%s".formatted(workflowModuleId, extension);
          return WorkflowModuleConfigFiles
              .locationsOf(workflowModuleId, filename)
              .stream();
        })
        .flatMap(location -> loadConfigSources(location, ordinal, classLoader).stream())
        .toList();

  }

  /**
   * Files on the file system are not supported. Workflow module properties have to be placed
   * in a workflow module.
   *
   * @param uri the {@link URI} to load the {@link ConfigSource}.
   * @param ordinal the ordinal of the {@link ConfigSource}.
   * @return An empty list
   */
  @Override
  protected List<ConfigSource> tryFileSystem(
      final URI uri,
      final int ordinal) {

    return emptyList();

  }

  /**
   * Public, because WorkflowModuleBuildStepProcessor uses it. It collects the files to watch
   * and to embed into a native image.
   *
   * @return The file extensions supported
   */
  public String[] getFileExtensions() {

    return super.getFileExtensions();

  }

}
