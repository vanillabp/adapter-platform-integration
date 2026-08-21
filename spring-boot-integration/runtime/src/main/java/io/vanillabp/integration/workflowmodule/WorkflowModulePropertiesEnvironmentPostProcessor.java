package io.vanillabp.integration.workflowmodule;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;

/**
 * An {@link EnvironmentPostProcessor} that loads workflow module-specific
 * YAML and properties files into the Spring {@link ConfigurableEnvironment}.
 *
 * <p>For each workflow module discovered via {@code META-INF/workflow-module}
 * classpath resources, the following files are loaded (if present):
 * <ul>
 *   <li>{@code {moduleId}.yaml} / {@code {moduleId}.yml}</li>
 *   <li>{@code {moduleId}.properties}</li>
 *   <li>{@code {moduleId}-{profile}.yaml} / {@code {moduleId}-{profile}.yml} (for each active profile)</li>
 *   <li>{@code {moduleId}-{profile}.properties} (for each active profile)</li>
 * </ul>
 *
 * <p>Files are searched in the following classpath locations (analogous to
 * Spring Boot's own {@code application.yaml} resolution which covers both
 * root and {@code config/}):
 * <ol>
 *   <li>{@code {filename}} — classpath root</li>
 *   <li>{@code config/{filename}} — config directory</li>
 *   <li>{@code {moduleId}/{filename}} — workflow module subdirectory</li>
 *   <li>{@code {moduleId}/config/{filename}} — config inside workflow module subdirectory</li>
 * </ol>
 * This allows workflow modules packaged as separate Maven/Gradle modules to
 * place their configuration files in a module-specific subdirectory, avoiding
 * classpath conflicts with other modules.
 *
 * <p>A workflow module ships <b>defaults</b>: its files are appended at the very
 * end of the environment, below every source the application brings - system
 * properties, environment variables, {@code application.yaml} and
 * {@code application.properties} wherever they live, an external configuration
 * file, {@code defaultProperties}. Whatever the application configures wins,
 * whichever file it uses. Among the module's own files the order is unchanged:
 * profile-specific variants beat base variants, and YAML beats
 * {@code .properties} for the same base name. Quarkus answers the same way
 * (see {@code WorkflowModuleBuildStepProcessor}).
 *
 * <p>Appending instead of inserting after a named source is deliberate. An
 * application may bring config data sources this integration cannot know about
 * ({@code spring.config.import}, {@code spring.config.additional-location}, a
 * source contributed by another {@link EnvironmentPostProcessor}), and matching
 * the name of one known source would put the module's files above all of them.
 * The end of the list is the only position which stays correct whatever the
 * application brings.
 *
 * <p><b>Limitation:</b> Multi-document YAML using
 * {@code spring.config.activate.on-profile} is not supported inside workflow
 * module config files — profile-specific values must be placed in files using
 * the profile file-name suffix (e.g. {@code {moduleId}-{profile}.yaml}).
 */
public class WorkflowModulePropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final String CLASSPATH_PATTERN = "classpath*:%s";

  private final PropertiesPropertySourceLoader propertiesLoader = new PropertiesPropertySourceLoader();
  private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

  /**
   * Run after {@link ConfigDataEnvironmentPostProcessor} so that
   * {@code application.yaml} is already loaded and active profiles
   * are known.
   */
  @Override
  public int getOrder() {

    return ConfigDataEnvironmentPostProcessor.ORDER + 1;

  }

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment,
      final SpringApplication application) {

    final var resourceLoader = application.getResourceLoader();

    final var workflowModuleIds = WorkflowModuleAutoConfiguration
        .determineWorkflowModules(resourceLoader)
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .toList();

    if (workflowModuleIds.isEmpty()) {
      return;
    }

    final var activeProfiles = environment.getActiveProfiles();
    final var resolver = ResourcePatternUtils
        .getResourcePatternResolver(resourceLoader);

    for (final var moduleId : workflowModuleIds) {
      // collect property sources ordered by priority (highest first):
      // profile-specific files (last active profile first) before base files,
      // YAML before .properties for the same base name
      final var propertySources = new LinkedList<PropertySource<?>>();
      for (var i = activeProfiles.length - 1; i >= 0; --i) {
        propertySources.addAll(load(resolver, moduleId, activeProfiles[i]));
      }
      propertySources.addAll(load(resolver, moduleId, null));

      // append below every source the application brings: adding in the order
      // collected above keeps the priority order among the module's own files,
      // and the end of the list needs no assumption about what the application
      // put into its environment
      propertySources
          .forEach(propertySource -> environment.getPropertySources().addLast(propertySource));
    }

  }

  /**
   * Load property sources for a given module ID and optional profile,
   * ordered by priority (highest first): YAML before .properties.
   */
  private List<PropertySource<?>> load(
      final ResourcePatternResolver resolver,
      final String moduleId,
      @Nullable final String profile) {

    final var baseName = profile != null
        ? "%s-%s".formatted(moduleId, profile)
        : moduleId;

    final var result = new LinkedList<PropertySource<?>>();
    result.addAll(loadResources(resolver, moduleId, baseName, yamlLoader));
    result.addAll(loadResources(resolver, moduleId, baseName, propertiesLoader));
    return result;

  }

  /**
   * Load all property sources for files matching the given base name
   * using the given loader. Files are searched in multiple classpath
   * locations: root, config/, {moduleId}/, and {moduleId}/config/.
   */
  private List<PropertySource<?>> loadResources(
      final ResourcePatternResolver resolver,
      final String moduleId,
      final String baseName,
      final PropertySourceLoader loader) {

    // Search locations analogous to Spring Boot's application.yaml resolution,
    // plus workflow module subdirectory variants
    final var searchPrefixes = List.of(
        "",
        "config/",
        "%s/".formatted(moduleId),
        "%s/config/".formatted(moduleId));

    return Arrays.stream(loader.getFileExtensions())
        .flatMap(extension -> {
          final var filename = "%s.%s".formatted(baseName, extension);
          return searchPrefixes.stream()
              .flatMap(prefix -> {
                final var location = "%s%s".formatted(prefix, filename);
                try {
                  final var resources = resolver.getResources(
                      CLASSPATH_PATTERN.formatted(location));
                  return Arrays.stream(resources)
                      .filter(Resource::exists)
                      .flatMap(resource -> {
                        try {
                          return loader
                              .load("workflowmodule:%s".formatted(location), resource)
                              .stream();
                        } catch (IOException e) {
                          return java.util.stream.Stream.empty();
                        }
                      });
                } catch (IOException e) {
                  return java.util.stream.Stream.empty();
                }
              });
        })
        .toList();

  }

}
