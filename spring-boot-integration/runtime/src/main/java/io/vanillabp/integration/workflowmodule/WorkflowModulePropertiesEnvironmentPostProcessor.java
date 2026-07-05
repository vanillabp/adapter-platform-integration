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
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
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
 * <p>Workflow module property sources are inserted right below the system
 * environment (i.e. below system properties and environment variables but with
 * higher priority than {@code application.yaml}/{@code application.properties}),
 * matching the behavior of the Quarkus implementation. Profile-specific variants
 * have higher priority than base variants. YAML has higher priority than
 * {@code .properties} for the same base name.
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

      // insert right below system properties and environment variables but above
      // all config files: adding after the system environment property source in
      // reverse order keeps the priority order collected above
      propertySources
          .reversed()
          .forEach(propertySource -> addPropertySource(environment.getPropertySources(), propertySource));
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

  /**
   * Add a property source right below system properties and environment
   * variables but above all config files. Using the well-known name of the
   * system environment property source is stable across Spring Boot versions
   * (in contrast to matching names of config data property sources).
   */
  private void addPropertySource(
      final MutablePropertySources propertySources,
      final PropertySource<?> propertySource) {

    if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
      propertySources.addAfter(
          StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
          propertySource);
    } else {
      propertySources.addFirst(propertySource);
    }

  }

}
