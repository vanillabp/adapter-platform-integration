package io.vanillabp.integration.runtime.config;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.AbstractLocationConfigSourceLoader;
import io.smallrye.config.PropertiesConfigSource;
import lombok.RequiredArgsConstructor;

/**
 * A config source provider loading properties-files named by a workflow module ID.
 * The super class is responsible for determining profile-based variants.
 *
 * <p>Files are searched both at the classpath root and inside a subdirectory
 * named after the workflow module ID. This allows workflow modules packaged
 * as separate Maven/Gradle modules to place their configuration in a
 * module-specific subdirectory (avoiding classpath conflicts).
 */
@StaticInitSafe
@RequiredArgsConstructor
public class WorkflowModuleSpecificPropertiesConfigSourceProvider extends AbstractLocationConfigSourceLoader implements ConfigSourceProvider {

  private static final String[] PROPS_EXTENSIONS = new String[]{
      "properties"
  };

  /**
   * The workflow module ID
   */
  private final String workflowModuleId;

  /**
   * The ordinal/priority
   */
  private final int ordinal;

  /**
   * Made public to be used by WorkflowModuleBuildStepProcessor when it collects the files to watch and to embed into a native image.
   *
   * @return The file extensions supported
   */
  public String[] getFileExtensions() {

    return PROPS_EXTENSIONS;

  }

  /**
   * Building a {@link PropertiesConfigSource} able of loading properties-files.
   *
   * @param url the {@link URL} to load the {@link ConfigSource}.
   * @param ordinal the ordinal of the {@link ConfigSource}.
   * @return The config source
   * @throws IOException Thrown if file cannot be loaded
   */
  protected ConfigSource loadConfigSource(
      final URL url,
      final int ordinal) throws IOException {

    return new PropertiesConfigSource(url, ordinal);

  }

  /**
   * Determine all config sources for all known file extensions.
   * Searches both at the classpath root ({@code {id}.properties}) and in
   * a workflow module subdirectory ({@code {id}/{id}.properties}).
   *
   * @param classLoader
   *            the class loader, which should be used for discovery and resource loading purposes
   * @return The config sources
   */
  public List<ConfigSource> getConfigSources(
      final ClassLoader classLoader) {

    return Arrays
        .stream(PROPS_EXTENSIONS)
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
  protected List<ConfigSource> tryFileSystem(
      final URI uri,
      final int ordinal) {

    return Collections.emptyList();

  }

}
