package io.vanillabp.integration.workflowmodule;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;

import io.vanillabp.integration.adapter.migration.config.WorkflowModuleConfigFiles;

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
 * <p>Files are searched in the classpath locations {@link WorkflowModuleConfigFiles}
 * names:
 * <ol>
 *   <li>{@code {filename}}: classpath root</li>
 *   <li>{@code config/{filename}}: config directory</li>
 *   <li>{@code {moduleId}/{filename}}: workflow module subdirectory</li>
 *   <li>{@code {moduleId}/config/{filename}}: config inside workflow module subdirectory</li>
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
 * <p>A file named after a profile is read here also where the file without the
 * profile is missing. Quarkus reads it only where the two lie next to each other, so
 * such a file is named at startup together with the file which would make Quarkus read
 * it (see decision 61 in the repository's DECISIONS.md).
 *
 * <p>The four locations are the ones {@link WorkflowModuleConfigFiles} names, and Quarkus
 * reads the same four. They are styles rather than a ranking, so a file belongs in exactly
 * one of them: the same file found in two of them ends the boot rather than let one of the
 * two win (see decision 65 in the repository's DECISIONS.md).
 *
 * <p><b>Limitation:</b> Multi-document YAML using
 * {@code spring.config.activate.on-profile} is not supported inside workflow
 * module config files. Profile-specific values are placed in files using
 * the profile file-name suffix (e.g. {@code {moduleId}-{profile}.yaml}).
 */
public class WorkflowModulePropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final String CLASSPATH_PATTERN = "classpath*:%s";

  private final PropertiesPropertySourceLoader propertiesLoader = new PropertiesPropertySourceLoader();
  private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

  /**
   * An environment post processor runs before the logging system is up, so its lines are
   * collected and replayed once it is. Spring Boot hands the factory to the constructor.
   */
  private final Log log;

  /**
   * Built by Spring Boot, which reads this class from
   * <code>META-INF/spring.factories</code> and passes the factory it finds in the
   * signature. An environment post processor is created long before the application
   * context, so nothing may be injected here and nothing may be logged directly.
   *
   * @param logFactory Builds the log which keeps its lines until the logging system is up
   */
  public WorkflowModulePropertiesEnvironmentPostProcessor(
      final DeferredLogFactory logFactory) {

    this.log = logFactory.getLog(WorkflowModulePropertiesEnvironmentPostProcessor.class);

  }

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
      // collect the files ordered by priority (highest first): profile-specific
      // files (last active profile first) before plain files, YAML before
      // .properties for the same base name
      final var profileFiles = new LinkedList<ConfigFile>();
      for (var i = activeProfiles.length - 1; i >= 0; --i) {
        profileFiles.addAll(configFiles(resolver, moduleId, activeProfiles[i]));
      }
      final var plainFiles = configFiles(resolver, moduleId, null);

      // append below every source the application brings: adding in the order
      // collected above keeps the priority order among the module's own files,
      // and the end of the list needs no assumption about what the application
      // put into its environment
      Stream
          .concat(profileFiles.stream(), plainFiles.stream())
          .flatMap(ConfigFile::load)
          .forEach(propertySource -> environment.getPropertySources().addLast(propertySource));

      messageAboutProfileFilesQuarkusWouldNotRead(profileFiles, plainFiles)
          .ifPresent(log::warn);
      WorkflowModuleConfigFiles
          .messageAboutAFileFoundInMoreThanOnePlace(
              moduleId,
              placesPerFilename(Stream.concat(profileFiles.stream(), plainFiles.stream())))
          .ifPresent(reason -> {
            throw new IllegalStateException(reason);
          });
    }

  }

  /**
   * Reports the files of a workflow module which this application reads and a Quarkus
   * application would not.
   * <p>
   * SmallRye, and with it Quarkus, loads {@code id-{profile}.yaml} only where {@code id.yaml}
   * lies in the same place, while Spring Boot reads every file it finds by name. A workflow
   * module is a library and ends up on either platform, so a module which ships a file for one
   * profile and nothing else works here and quietly ships nothing there. Saying it where the
   * module is built is the cheapest place to learn it (see decision 61 in the repository's
   * DECISIONS.md).
   *
   * @param profileFiles The profile-specific files of one workflow module found in the classpath
   * @param plainFiles The files without a profile of the same workflow module
   * @return The warning, or nothing where every profile-specific file has its plain file
   */
  private static Optional<String> messageAboutProfileFilesQuarkusWouldNotRead(
      final List<ConfigFile> profileFiles,
      final List<ConfigFile> plainFiles) {

    final var filesWithoutTheirPlainFile = profileFiles
        .stream()
        .filter(profileFile -> plainFiles
            .stream()
            .noneMatch(profileFile::liesNextTo))
        .map(ConfigFile::describeTheMissingPlainFile)
        .flatMap(Optional::stream)
        .distinct()
        .sorted()
        .toList();
    if (filesWithoutTheirPlainFile.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of("""
        Configuration files of workflow modules which a Quarkus application would not read:
          %s
        Spring Boot reads them, Quarkus reads a file carrying a profile in its name only \
        where the file without the profile lies in the same place. Add the file each line \
        asks for, in the same workflow module, and the module works on both platforms. An \
        empty file is enough.""".formatted(String.join("\n  ", filesWithoutTheirPlainFile)));

  }

  /**
   * Sorts the files of one workflow module into the places they were found at, so that a
   * file shipped twice can be seen.
   *
   * @param configFiles The files of one workflow module found in the classpath
   * @return The places each of them was found at, keyed by the file's name
   */
  private static Map<String, Set<String>> placesPerFilename(
      final Stream<ConfigFile> configFiles) {

    final var placesPerFilename = new TreeMap<String, Set<String>>();
    configFiles
        .forEach(configFile -> placesPerFilename
            // a location found through two prefixes of the same module is one place, which
            // is what a module whose ID is "config" produces
            .computeIfAbsent(configFile.filename(), name -> new TreeSet<>())
            .add(configFile.location()));
    return placesPerFilename;

  }

  /**
   * Find the files of a given module ID and optional profile, ordered by
   * priority (highest first): YAML before .properties.
   */
  private List<ConfigFile> configFiles(
      final ResourcePatternResolver resolver,
      final String moduleId,
      @Nullable final String profile) {

    final var baseName = profile != null
        ? "%s-%s".formatted(moduleId, profile)
        : moduleId;

    final var result = new LinkedList<ConfigFile>();
    result.addAll(findResources(resolver, moduleId, baseName, yamlLoader));
    result.addAll(findResources(resolver, moduleId, baseName, propertiesLoader));
    return result;

  }

  /**
   * Find all files matching the given base name for the given loader, in each of the
   * four places a workflow module may put a file.
   */
  private List<ConfigFile> findResources(
      final ResourcePatternResolver resolver,
      final String moduleId,
      final String baseName,
      final PropertySourceLoader loader) {

    final var searchPrefixes = WorkflowModuleConfigFiles.directoriesOf(moduleId);

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
                      .map(resource -> new ConfigFile(moduleId, location, resource, loader));
                } catch (IOException e) {
                  return Stream.<ConfigFile>empty();
                }
              });
        })
        .toList();

  }

  /**
   * A configuration file of a workflow module found in the classpath, together with the
   * loader which reads its extension. Two files are read by the same loader when they are
   * both YAML or both {@code .properties}, and only such a pair counts for the rule Quarkus
   * applies.
   *
   * @param moduleId The ID of the workflow module the file belongs to
   * @param location The classpath location the file was found at
   * @param resource The file itself
   * @param loader The loader reading this kind of file
   */
  private record ConfigFile(
                            String moduleId,
                            String location,
                            Resource resource,
                            PropertySourceLoader loader) {

    /**
     * @return The property sources of this file, none where it cannot be read
     */
    Stream<PropertySource<?>> load() {

      try {
        return loader
            .load("workflowmodule:%s".formatted(location), resource)
            .stream();
      } catch (IOException e) {
        return Stream.empty();
      }

    }

    /**
     * @param plainFile A file of the same workflow module which carries no profile
     * @return Whether that file is the one Quarkus would pair this one with: same directory,
     *         same archive, and an extension of the same loader
     */
    boolean liesNextTo(
        final ConfigFile plainFile) {

      final var directory = directory();
      return loader.equals(plainFile.loader()) && directory.isPresent() && directory.equals(plainFile.directory());

    }

    /**
     * @return The file and the plain files which would make Quarkus read it, or nothing
     *         where its location cannot be named
     */
    Optional<String> describeTheMissingPlainFile() {

      return directory()
          .map(directory -> "%s, which needs %s next to it".formatted(
              url().orElse(location),
              Arrays
                  .stream(loader.getFileExtensions())
                  .map(extension -> "'%s.%s'".formatted(moduleId, extension))
                  .collect(Collectors.joining(" or "))));

    }

    /**
     * @return The name of the file, without the classpath location it was found at
     */
    String filename() {

      return location.substring(location.lastIndexOf('/') + 1);

    }

    /**
     * @return The place the file lies in, which is its URL up to and including the last
     *         slash, so that a file in a jar is not taken for one in a directory
     */
    private Optional<String> directory() {

      return url()
          .map(url -> url.substring(0, url.lastIndexOf('/') + 1));

    }

    /**
     * @return The URL of the file, nothing where the resource cannot name one
     */
    private Optional<String> url() {

      try {
        return Optional.of(resource.getURL().toString());
      } catch (IOException e) {
        return Optional.empty();
      }

    }

  }

}
