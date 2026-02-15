package io.vanillabp.integration.workflowmodule;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;

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
 * <p>Workflow module property sources are inserted with higher priority than
 * {@code application.yaml}/{@code application.properties}, matching the
 * behavior of the Quarkus implementation. Profile-specific variants have
 * higher priority than base variants. YAML has higher priority than
 * {@code .properties} for the same base name.
 */
public class WorkflowModulePropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final String CLASSPATH_PATTERN = "classpath*:%s";

  private final PropertiesPropertySourceLoader propertiesLoader = new PropertiesPropertySourceLoader();
  private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

  /**
   * Run after {@code ConfigDataEnvironmentPostProcessor} so that
   * {@code application.yaml} is already loaded and active profiles
   * are known.
   */
  @Override
  public int getOrder() {

    return Ordered.HIGHEST_PRECEDENCE + 11;

  }

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment,
      final SpringApplication application) {

    final var workflowModuleIds = WorkflowModuleAutoConfiguration
        .determineWorkflowModules(null)
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .toList();

    if (workflowModuleIds.isEmpty()) {
      return;
    }

    final var propertySources = environment.getPropertySources();
    final var activeProfiles = environment.getActiveProfiles();

    // Find the insertion point: before "Config resource" sources
    // (which represent application.yaml/application.properties).
    // We insert workflow module sources so they have higher priority
    // than application config but lower priority than system properties
    // and environment variables.
    final var insertBeforeName = findApplicationConfigSourceName(propertySources)
        .orElse(null);

    final var resolver = ResourcePatternUtils
        .getResourcePatternResolver(null);
    for (final var moduleId : workflowModuleIds) {
      // Load base files (lower priority)
      loadAndAdd(resolver, propertySources, insertBeforeName, moduleId, null);

      // Load profile-specific files (higher priority — added before base)
      for (final var profile : activeProfiles) {
        loadAndAdd(resolver, propertySources, insertBeforeName, moduleId, profile);
      }
    }

  }

  /**
   * Load property sources for a given module ID and optional profile,
   * then insert them into the environment.
   */
  private void loadAndAdd(
      final ResourcePatternResolver resolver,
      final MutablePropertySources propertySources,
      @Nullable final String insertBeforeName,
      final String moduleId,
      @Nullable final String profile) {

    final var baseName = profile != null
        ? "%s-%s".formatted(moduleId, profile)
        : moduleId;

    // Load .yaml / .yml files (higher priority, loaded first so they end up
    // further from insertBefore in the property source list)
    loadResources(resolver, moduleId, baseName, yamlLoader)
        .forEach(ps -> addPropertySource(propertySources, insertBeforeName, ps));

    // Load .properties files (lower priority than YAML)
    loadResources(resolver, moduleId, baseName, propertiesLoader)
        .forEach(ps -> addPropertySource(propertySources, insertBeforeName, ps));

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
      final org.springframework.boot.env.PropertySourceLoader loader) {

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
   * Add a property source before the application config sources,
   * or at the end if no application config source was found.
   */
  private void addPropertySource(
      final MutablePropertySources propertySources,
      @Nullable final String insertBeforeName,
      final PropertySource<?> propertySource) {

    if (insertBeforeName != null) {
      propertySources.addBefore(insertBeforeName, propertySource);
    } else {
      propertySources.addLast(propertySource);
    }

  }

  /**
   * Find the name of the first application config property source.
   * In Spring Boot 3.x, these are typically named starting with
   * "Config resource".
   */
  private Optional<String> findApplicationConfigSourceName(
      final MutablePropertySources propertySources) {

    for (final var ps : propertySources) {
      if (ps.getName().startsWith("Config resource")) {
        return Optional.of(ps.getName());
      }
    }
    return Optional.empty();

  }

}
