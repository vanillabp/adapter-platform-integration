package io.vanillabp.integration.runtime.config;

import static java.util.Collections.emptyList;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

import io.smallrye.config.source.yaml.YamlConfigSourceLoader;
import lombok.RequiredArgsConstructor;

/**
 * A config source provider loading config files named by a workflow module ID.
 * The super class is responsible for determining profile-based variants.
 */
@RequiredArgsConstructor
public class WorkflowModuleSpecificYamlConfigSourceProvider extends YamlConfigSourceLoader implements ConfigSourceProvider {

  /**
   * The workflow module ID
   */
  private final String workflowModuleId;

  /**
   * The ordinal/priority
   */
  private final int ordinal;

  /**
   * Determine all config sources for all known file extensions.
   *
   * @param classLoader
   *            the class loader which should be used for discovery and resource loading purposes
   * @return The config sources
   */
  @Override
  public List<ConfigSource> getConfigSources(
      final ClassLoader classLoader) {

    return Arrays
        .stream(getFileExtensions())
        .map(extension -> "%s.%s".formatted(workflowModuleId, extension))
        .flatMap(filename -> loadConfigSources(filename, ordinal, classLoader).stream())
        .toList();

  }

  /**
   * Do not support files since workflow module properties have to placed in a workflow module.
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
   * Made public to be used by WorkflowModuleBuildStepProcessor#watchWorkflowModuleSpecificConfigFiles.
   *
   * @return The file extensions supported
   */
  public String[] getFileExtensions() {

    return super.getFileExtensions();

  }

}
