package io.vanillabp.integration.runtime.config;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.AbstractLocationConfigSourceLoader;
import io.smallrye.config.PropertiesConfigSource;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleConfigFiles;

/**
 * A config source provider loading properties-files named by a workflow module ID.
 * The super class is responsible for determining profile-based variants.
 *
 * <p>Files are searched at the four places a workflow module may put them,
 * which {@link WorkflowModuleConfigFiles} names and the Spring Boot integration
 * searches as well. A module packaged as its own Maven or Gradle module can
 * therefore keep its configuration next to its other resources, and a module
 * which prefers a {@code config} directory can do that instead.
 */
@StaticInitSafe
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
   * Built once per workflow module by the config builder which knows the modules of
   * this application.
   *
   * @param workflowModuleId The module whose file this provider looks for - the file is
   *          named after the module
   * @param ordinal Where the values of that file rank against the other configuration
   *          sources
   */
  public WorkflowModuleSpecificPropertiesConfigSourceProvider(
      final String workflowModuleId,
      final int ordinal) {

    this.workflowModuleId = workflowModuleId;
    this.ordinal = ordinal;

  }

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
   * Determine all config sources for all known file extensions, at each of the
   * four places a workflow module may put a file.
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
          return WorkflowModuleConfigFiles
              .locationsOf(workflowModuleId, filename)
              .stream();
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
