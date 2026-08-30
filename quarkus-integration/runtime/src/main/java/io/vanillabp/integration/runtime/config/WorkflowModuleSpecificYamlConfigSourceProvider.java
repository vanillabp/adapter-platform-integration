package io.vanillabp.integration.runtime.config;

import static java.util.Collections.emptyList;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.source.yaml.YamlConfigSourceLoader;
import lombok.RequiredArgsConstructor;

/**
 * A config source provider loading config files named by a workflow module ID.
 * The super class is responsible for determining profile-based variants.
 *
 * <p>Files are searched both at the classpath root and inside a subdirectory
 * named after the workflow module ID. This allows workflow modules packaged
 * as separate Maven/Gradle modules to place their configuration in a
 * module-specific subdirectory (avoiding classpath conflicts).
 */
@StaticInitSafe
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
   * Searches both at the classpath root ({@code {id}.yaml}) and in
   * a workflow module subdirectory ({@code {id}/{id}.yaml}).
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
          return Stream.of(
              filename,
              "%s/%s".formatted(workflowModuleId, filename));
        })
        .flatMap(location -> loadConfigSources(location, ordinal, classLoader).stream())
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
   * Made public to be used by WorkflowModuleBuildStepProcessor when it collects the files to watch and to embed into a native image.
   *
   * @return The file extensions supported
   */
  public String[] getFileExtensions() {

    return super.getFileExtensions();

  }

}
