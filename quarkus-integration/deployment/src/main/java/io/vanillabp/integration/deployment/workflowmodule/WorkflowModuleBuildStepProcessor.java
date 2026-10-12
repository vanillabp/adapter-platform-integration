package io.vanillabp.integration.deployment.workflowmodule;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.runtime.configuration.ConfigBuilder;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleConfigFiles;
import io.vanillabp.integration.runtime.config.WorkflowModuleConfigFilesRecorder;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificPropertiesConfigBuilder;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificPropertiesConfigSourceProvider;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificYamlConfigBuilder;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificYamlConfigSourceProvider;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import lombok.extern.slf4j.Slf4j;

/**
 * Build step processor of the VanillaBP extension. It processes the workflow modules.
 * <p>
 * A workflow module is a Maven or Gradle module. It carries its BPMN files, its workflow
 * services and its own configuration files. The modules are found while the application is
 * built, and the classes which read those files are generated then as well. Quarkus takes
 * the config sources of an application from what the build registered. And a native image
 * only carries the files it was told about. What a build does not see is not read later.
 */
@Slf4j
public class WorkflowModuleBuildStepProcessor {

  /**
   * Quarkus builds this processor while it augments the application and calls the build
   * steps below on it. Nothing else builds it, and no step keeps state in it.
   */
  public WorkflowModuleBuildStepProcessor() {
  }

  /**
   * Priority of the YAML config files of a workflow module. It is below the classpath
   * "application.yaml" (255), because a workflow module ships defaults and the application
   * always wins.
   * <p>
   * The distance to {@link #PROPERTIES_CONFIGFILE_ORDINAL} is 5. SmallRye keeps the same
   * distance between "application.yaml" and "application.properties". So YAML beats
   * properties among a module's own files, just like among the application's files. The
   * distance to the application's weakest file (250) leaves room for the profiles. SmallRye
   * loads a profile-specific file at the ordinal of its base file plus one for each active
   * profile ({@code AbstractLocationConfigSourceLoader.ConfigurableProfileConfigSourceFactory}).
   * So even a dozen active profiles cannot lift a module file above "application.properties".
   *
   * @see WorkflowModuleBuildStepProcessor#buildWorkflowModuleSpecificConfigFilesConfigBuilder(List, VanillaBpWorkflowModulesBuildItem, BuildProducer)
   */
  public static final int YAML_CONFIGFILE_ORDINAL = 235;

  /**
   * Priority of the properties config files of a workflow module. It is below the classpath
   * "application.properties" (250) and below {@link #YAML_CONFIGFILE_ORDINAL}, see there.
   *
   * @see WorkflowModuleBuildStepProcessor#buildWorkflowModuleSpecificConfigFilesConfigBuilder(List, VanillaBpWorkflowModulesBuildItem, BuildProducer)
   */
  public static final int PROPERTIES_CONFIGFILE_ORDINAL = 230;

  /**
   * Finds all workflow modules. Each one has a <code>META-INF/workflow-module</code> file in its
   * Maven or Gradle module. The result is keyed by the archive which holds this descriptor.
   * More than one archive may provide the same workflow module, because two modules with the
   * same ID are equal.
   *
   * @param applicationArchivesBuildItem The archives part of this Quarkus build
   * @return A build item holding meta-information of all workflow modules
   */
  @BuildStep
  VanillaBpWorkflowModulesBuildItem findAllWorkflowModules(
      final ApplicationArchivesBuildItem applicationArchivesBuildItem) {

    final var workflowModules = applicationArchivesBuildItem
        .getAllArchives()
        .stream()
        .flatMap(archive -> Optional
            .ofNullable(archive.getChildPath(WorkflowModule.METAINF_WORKFLOWMODULE))
            .map(Path::toUri)
            .flatMap(sourceUri -> WorkflowModuleBuildStepProcessor
                .readWorkflowModuleDescriptor(sourceUri)
                .map(id -> Map.entry(archive, WorkflowModule
                    .builder()
                    .id(id)
                    // NOTE: sourceUri only works on the BUILD machine and in dev mode.
                    // It points into the augmentation classpath. Code which runs in
                    // prod mode must never open it. It is kept to find the workflow
                    // module (e.g. its resources) at build time
                    .sourceUri(sourceUri)
                    .global(archive.equals(applicationArchivesBuildItem.getRootArchive()))
                    .build())))
            .stream())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    return new VanillaBpWorkflowModulesBuildItem(workflowModules);

  }

  /**
   * Builds the {@link ConfigBuilder} classes which load the properties files of a workflow
   * module. Such a file is named after the workflow module's ID instead of
   * <code>application.properties</code>. These files help to keep workflow modules apart. The
   * configuration can live in the same Maven or Gradle module as the code and the BPMS
   * resources (e.g. BPMN files).
   *
   * <table>
   *   <caption>Default ConfigSource ordinals (Quarkus / SmallRye) and ordinals of workflow module config sources</caption>
   *   <thead>
   *     <tr>
   *       <th>Config Source</th>
   *       <th style="min-width:120px;">Ordinal</th>
   *       <th>Notes</th>
   *     </tr>
   *   </thead>
   *   <tbody>
   *     <tr>
   *       <td>System properties (e.g. <code>-Dfoo=bar</code>)</td>
   *       <td style="white-space:nowrap;">400</td>
   *       <td>Highest priority (MicroProfile / SmallRye standard)</td>
   *     </tr>
   *     <tr>
   *       <td>Environment variables</td>
   *       <td style="white-space:nowrap;">300</td>
   *       <td>Typical OS / container environment variables</td>
   *     </tr>
   *     <tr>
   *       <td><code>.env</code> file in the current working directory</td>
   *       <td style="white-space:nowrap;">295</td>
   *       <td>Optional, if present Quarkus loads it automatically</td>
   *     </tr>
   *     <tr>
   *       <td><code>$PWD/config/application.yaml</code> (config directory, if <code>quarkus-config-yaml</code> is enabled)</td>
   *       <td style="white-space:nowrap;">265</td>
   *       <td>Filesystem YAML has higher priority than classpath YAML</td>
   *     </tr>
   *     <tr>
   *       <td><code>$PWD/config/application.properties</code> (config directory next to runner)</td>
   *       <td style="white-space:nowrap;">260</td>
   *       <td>External configuration file outside of the JAR</td>
   *     </tr>
   *     <tr>
   *       <td><code>application.yaml</code> (classpath, if <code>quarkus-config-yaml</code> is enabled)</td>
   *       <td style="white-space:nowrap;">255</td>
   *       <td>YAML on classpath; Quarkus gives YAML higher priority than classpath properties</td>
   *     </tr>
   *     <tr>
   *       <td><code>application.properties</code> (classpath, e.g. <code>src/main/resources</code>)</td>
   *       <td style="white-space:nowrap;">250</td>
   *       <td>Default classpath application.properties</td>
   *     </tr>
   *     <tr>
   *       <td><b><code>XXX.yaml</code> (classpath, if <code>quarkus-config-yaml</code> is enabled)</b></td>
   *       <td style="white-space:nowrap;"><b>235</b></td>
   *       <td><b>YAML on the classpath specific to a certain workflow module having ID &quot;XXX&quot;.
   *       A workflow module ships defaults, so everything the application configures wins over it -
   *       its classpath files as much as a file next to the runner.</b></td>
   *     </tr>
   *     <tr>
   *       <td><b><code>XXX.properties</code> (classpath, e.g. <code>src/main/resources</code>)</b></td>
   *       <td style="white-space:nowrap;"><b>230</b></td>
   *       <td><b>Properties on the classpath specific to a certain workflow module having ID &quot;XXX&quot;.
   *       Below the module's own YAML, mirroring the distance between the application's two files.</b></td>
   *     </tr>
   *     <tr>
   *       <td><code>META-INF/microprofile-config.properties</code> (classpath)</td>
   *       <td style="white-space:nowrap;">100</td>
   *       <td>MicroProfile standard source (lowest practical priority)</td>
   *     </tr>
   *   </tbody>
   * </table>
   *
   * <p>Profiles are not in the table, because they are no separate rows. SmallRye loads
   * <code>name-{profile}.ext</code> at the ordinal of <code>name.ext</code>, plus one for each
   * active profile. So <code>XXX-prod.yaml</code> beats <code>XXX.yaml</code>, and
   * <code>application-prod.yaml</code> beats <code>application.yaml</code>, each within its own
   * band. The 15 between 235 and 250 keeps the two bands apart.
   *
   * <p>The two module ordinals used to be 256 and 251, one step ABOVE the application's
   * classpath files. So <code>application.yaml</code> could not change the configuration of a
   * workflow module, although that is the normal place to do it. And Spring Boot answered the
   * same question differently. Now both platforms agree on the order: system properties,
   * environment variables, the application's configuration wherever it lives,
   * <code>XXX-{profile}</code>, <code>XXX</code>.
   *
   * @param features All features provided by extensions
   * @param workflowModules All workflow modules found
   * @param generatedClassesProducer Producer to build new classes
   * @return The build item holding the names of all {@link ConfigBuilder} classes
   */
  @BuildStep
  GeneratedConfigBuilderClassesBuildItem buildWorkflowModuleSpecificConfigFilesConfigBuilder(
      final List<FeatureBuildItem> features,
      final VanillaBpWorkflowModulesBuildItem workflowModules,
      final BuildProducer<GeneratedClassBuildItem> generatedClassesProducer) {

    final var configBuilderClassnames = new LinkedList<String>();

    // add xxx.properties files

    configBuilderClassnames.add(
        // get ConfigBuilder class generated to load files for all workflow modules
        addWorkflowModuleSpecificConfigBuilder(
            PROPERTIES_CONFIGFILE_ORDINAL,
            WorkflowModuleSpecificPropertiesConfigBuilder.class,
            workflowModules,
            generatedClassesProducer));

    // add xxx.yaml files

    final var yamlConfigExtensionAvailable = features
        .stream()
        .anyMatch(feature -> feature.getName().equals("config-yaml"));
    if (yamlConfigExtensionAvailable) {
      configBuilderClassnames.add(
          // get ConfigBuilder class generated to load files for all workflow modules
          addWorkflowModuleSpecificConfigBuilder(
              YAML_CONFIGFILE_ORDINAL,
              WorkflowModuleSpecificYamlConfigBuilder.class,
              workflowModules,
              generatedClassesProducer));
    }

    return GeneratedConfigBuilderClassesBuildItem
        .builder()
        .configBuilderClassnames(configBuilderClassnames)
        .build();

  }

  /**
   * Activates the {@link ConfigBuilder} classes generated for the files of the workflow
   * modules. This is only done for the custom properties of workflow modules, because the
   * VanillaBP properties are already needed during augmentation.
   * <p>
   * This is a separate step, so the generated classes are sure to exist. In one build step
   * there would be no guarantee that the classes exist when the configuration is built from
   * the {@link ConfigBuilder}.
   *
   * @param generatedConfigLoaders The build item holding the names of all {@link ConfigBuilder} classes
   * @param configFilesReported The report about the configuration files of the workflow
   *          modules which this application does not read. It is asked for here, because
   *          Quarkus drops a build step whose items nobody consumes. The report has to be
   *          written in every build
   * @param staticInitConfigProducer Producer for static initialization config builders
   * @param runTimeConfigProducer Producer for static runtime config builders
   */
  @BuildStep
  WorkflowModuleSpecificConfigBuilderBuildItem addWorkflowModuleSpecificConfigFiles(
      final GeneratedConfigBuilderClassesBuildItem generatedConfigLoaders,
      final WorkflowModuleConfigFilesReportedBuildItem configFilesReported,
      final BuildProducer<StaticInitConfigBuilderBuildItem> staticInitConfigProducer,
      final BuildProducer<RunTimeConfigBuilderBuildItem> runTimeConfigProducer) {

    generatedConfigLoaders
        .getConfigBuilderClassnames()
        .forEach(className -> {
          staticInitConfigProducer.produce(new StaticInitConfigBuilderBuildItem(className));
          runTimeConfigProducer.produce(new RunTimeConfigBuilderBuildItem(className));
        });

    return new WorkflowModuleSpecificConfigBuilderBuildItem();

  }

  /**
   * Adds a {@link ConfigBuilder} class which holds two things: the workflow module IDs, which
   * name the files to load, and the ordinal, which ranks this builder among the others.
   * <pre>
   * package of.abstract.config.builder.class;
   * import java.util.List;
   * public class ActualNameOfAbstractConfigBuilderClass extends NameOfAbstractConfigBuilderClass {
   *     protected int getOrdinal() {
   *          return 4711;
   *     }
   *     protected List&lt;String&gt; getWorkflowModuleIds() {
   *          return List.of("id1", "id2", ...);
   *     }
   * }
   * </pre>
   *
   * @param ordinal The ordinal to prioritize
   * @param abstractConfigBuilderClass The abstract class used as a super class for the {@link ConfigBuilder} class generated
   * @param workflowModules All workflow modules found
   * @param generatedClassesProducer Producer for new classes
   * @return The name of the class generated
   */
  private String addWorkflowModuleSpecificConfigBuilder(
      final int ordinal,
      final Class<? extends ConfigBuilder> abstractConfigBuilderClass,
      final VanillaBpWorkflowModulesBuildItem workflowModules,
      final BuildProducer<GeneratedClassBuildItem> generatedClassesProducer) {

    // the classname

    final var configBuilderClassname = "%s.Actual%s".formatted(
        abstractConfigBuilderClass.getPackageName(),
        abstractConfigBuilderClass.getSimpleName());

    // the class

    try (final var configBuilderClassCreator = ClassCreator
        .builder()
        .className(configBuilderClassname)
        .superClass(abstractConfigBuilderClass)
        /*
        .classOutput((
            className,
            data) -> {
          if (log.isDebugEnabled()) {
            log.debug("=== Gizmo generated: {} ===", className);
            final var reader = new ClassReader(data);
            final var writer = new StringWriter();
            final var traceClassVisitor = new TraceClassVisitor(new PrintWriter(writer));
            reader.accept(traceClassVisitor, 0);
            log.debug(writer.toString());
            log.debug("=== END {}  ===", className);
          }
        })
         */
        .classOutput(new GeneratedClassGizmoAdaptor(generatedClassesProducer, true))
        .build()) {

      // the method "getWorkflowModuleIds"

      try (final var methodCreator = configBuilderClassCreator
          .getMethodCreator("getWorkflowModuleIds", List.class)
          .setModifiers(Modifier.PROTECTED)) {

        final var workflowModuleIds = workflowModules
            .getWorkflowModules()
            .stream()
            .map(WorkflowModule::getId)
            .toList();

        final var array = methodCreator.newArray(String.class, workflowModuleIds.size());
        for (int i = 0; i < workflowModuleIds.size(); i++) {
          methodCreator.writeArrayValue(array, i, methodCreator.load(workflowModuleIds.get(i)));
        }

        final var list = methodCreator.invokeStaticMethod(
            MethodDescriptor.ofMethod(Arrays.class, "asList", List.class, Object[].class),
            array);

        methodCreator.returnValue(list);

      }

      // the method "getOrdinal"

      try (final var methodCreator = configBuilderClassCreator
          .getMethodCreator("getOrdinal", int.class)
          .setModifiers(Modifier.PROTECTED)) {

        methodCreator.returnInt(ordinal);

      }

    }

    return configBuilderClassname;

  }

  /**
   * Tells dev mode and the native image about the configuration files (properties or YAML)
   * of all workflow modules found. One rule says which names count, so the two cannot drift
   * apart. But each of them needs the rule in its own shape. Dev mode gets the rule itself.
   * The native image gets the files which match it in this build.
   * <p>
   * Dev mode gets a predicate and not a list of names, because a build writes such a list
   * once. A configuration file which a developer adds later is in no list a build produced. If
   * dev mode watched the names of the last build, that file would not trigger a restart. The
   * application would keep running with the configuration of that build. The predicate is as
   * narrow as the names which the config source providers read. So a YAML file which has
   * nothing to do with a workflow module restarts nothing.
   * <p>
   * A native image only carries the resources it was told about. If a file is watched in dev
   * mode but missing from the image, the workflow module silently runs with its defaults
   * instead of its settings.
   *
   * @param allWorkflowModules A {@link VanillaBpWorkflowModulesBuildItem} containing information about all workflow modules.
   * @param applicationArchives An {@link ApplicationArchivesBuildItem} containing the archives to be scanned for configuration files.
   * @param watchedFiles A {@link BuildProducer} that collects {@link HotDeploymentWatchedFileBuildItem} instances for hot deployment purposes.
   * @param nativeImageResources A {@link BuildProducer} putting the files into a native image.
   * @see WorkflowModuleSpecificPropertiesConfigSourceProvider#getConfigSources(ClassLoader)
   * @see WorkflowModuleSpecificYamlConfigSourceProvider#getConfigSources(ClassLoader)
   */
  @BuildStep
  void watchAndEmbedWorkflowModuleSpecificConfigFiles(
      final VanillaBpWorkflowModulesBuildItem allWorkflowModules,
      final ApplicationArchivesBuildItem applicationArchives,
      final BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
      final BuildProducer<NativeImageResourceBuildItem> nativeImageResources) {

    final var isWorkflowModuleConfigFile = workflowModuleSpecificConfigFileRule(allWorkflowModules);

    watchedFiles
        .produce(HotDeploymentWatchedFileBuildItem
            .builder()
            .setLocationPredicate(isWorkflowModuleConfigFile)
            .build());

    workflowModuleSpecificConfigFiles(applicationArchives, isWorkflowModuleConfigFile)
        .forEach(relativePath -> nativeImageResources.produce(new NativeImageResourceBuildItem(relativePath)));

  }

  /**
   * Reports the profile-specific configuration files of workflow modules which this
   * application does not read, and names the file each of them is missing.
   * <p>
   * SmallRye only loads <code>id-{profile}.yaml</code> where <code>id.yaml</code> lies in the
   * same place. It pairs the two, so the load order does not depend on which resource a class
   * loader returns first ({@code AbstractLocationConfigSourceLoader}). So a workflow module
   * which ships only a file for one profile ships settings nobody reads. Before this report,
   * nothing said so. The application starts, and the first sign is a value which differs from
   * the file. Spring Boot reads such a file. So a module author can build one without ever
   * meeting the rule (see decision 61 in the repository's DECISIONS.md).
   * <p>
   * The files are known while the application is built, but the report is written when it
   * starts. A developer reads the log of a start far more often than the log of a build. And
   * a native binary reports it the same way as the JVM.
   *
   * @param allWorkflowModules All workflow modules found
   * @param applicationArchives The archives of this Quarkus build
   * @param recorder The recorder writing the report at startup
   * @return The item saying the report is written, consumed by
   *         {@link #addWorkflowModuleSpecificConfigFiles(GeneratedConfigBuilderClassesBuildItem, WorkflowModuleConfigFilesReportedBuildItem, BuildProducer, BuildProducer)}
   */
  @Record(ExecutionTime.RUNTIME_INIT)
  @Consume(LoggingSetupBuildItem.class)
  @BuildStep
  WorkflowModuleConfigFilesReportedBuildItem reportConfigFilesWhichStayUnread(
      final VanillaBpWorkflowModulesBuildItem allWorkflowModules,
      final ApplicationArchivesBuildItem applicationArchives,
      final WorkflowModuleConfigFilesRecorder recorder) {

    messageAboutProfileFilesWithoutTheirPlainFile(
        workflowModuleSpecificConfigFilesPerArchive(
            applicationArchives,
            workflowModuleSpecificConfigFileRule(allWorkflowModules)),
        allWorkflowModules
            .getWorkflowModules()
            .stream()
            .map(WorkflowModule::getId)
            .toList())
        .ifPresent(recorder::report);

    final var configFiles = workflowModuleSpecificConfigFiles(
        applicationArchives,
        workflowModuleSpecificConfigFileRule(allWorkflowModules));
    allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .map(workflowModuleId -> WorkflowModuleConfigFiles.messageAboutAFileFoundInMoreThanOnePlace(
            workflowModuleId,
            placesPerFilename(workflowModuleId, configFiles)))
        .flatMap(Optional::stream)
        .forEach(recorder::refuseToStart);

    return new WorkflowModuleConfigFilesReportedBuildItem();

  }

  /**
   * Sorts the configuration files found by the places they were found at. So a file shipped
   * twice becomes visible. The files of the other workflow modules are dropped here. A path
   * only belongs to this module if one of the module's places plus the file's name gives that
   * path again.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param configFiles The paths of every workflow module configuration file found, relative
   *          to the root of the archive holding it
   * @return The places each file of that module was found at, keyed by the file's name
   */
  private static Map<String, List<String>> placesPerFilename(
      final String workflowModuleId,
      final Collection<String> configFiles) {

    final var placesPerFilename = new TreeMap<String, List<String>>();
    configFiles
        .forEach(configFile -> {
          final var filename = configFile.substring(configFile.lastIndexOf('/') + 1);
          if (WorkflowModuleConfigFiles
              .locationsOf(workflowModuleId, filename)
              .contains(configFile)) {
            placesPerFilename
                .computeIfAbsent(filename, name -> new LinkedList<>())
                .add(configFile);
          }
        });
    return placesPerFilename;

  }

  /**
   * Builds the report about the profile-specific files which stay unread. Each archive is
   * checked on its own. The file without the profile is only found if it lies in the same
   * archive and the same directory.
   *
   * @param configFilesPerArchive The configuration files of the workflow modules, per archive
   * @param workflowModuleIds The IDs of all workflow modules found
   * @return The warning, or nothing where every profile-specific file has its plain file
   */
  static Optional<String> messageAboutProfileFilesWithoutTheirPlainFile(
      final Collection<? extends Collection<String>> configFilesPerArchive,
      final Collection<String> workflowModuleIds) {

    final var filesWithoutTheirPlainFile = new TreeMap<String, String>();
    configFilesPerArchive
        .forEach(configFilesOfOneArchive -> configFilesOfOneArchive
            .forEach(configFile -> plainFileAProfileFileNeeds(
                configFile,
                configFilesOfOneArchive,
                workflowModuleIds)
                .ifPresent(plainFile -> filesWithoutTheirPlainFile.put(configFile, plainFile))));
    if (filesWithoutTheirPlainFile.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of("""
        Configuration files of workflow modules which this application does not read:
          %s
        Quarkus reads a file carrying a profile in its name only where the file without the \
        profile lies in the same place, so none of the settings above arrive. Add the file \
        each line asks for, in the same workflow module. An empty file is enough. Spring \
        Boot reads a profile file on its own, so a workflow module which has to run on both \
        platforms needs the plain file as well.""".formatted(
        filesWithoutTheirPlainFile
            .entrySet()
            .stream()
            .map(file -> "%s, which needs %s next to it".formatted(file.getKey(), file.getValue()))
            .collect(Collectors.joining("\n  "))));

  }

  /**
   * Says which file a profile-specific configuration file of a workflow module is missing.
   * A file with an extension of the other provider does not help. The properties provider and
   * the YAML provider each pair a profile-specific file only with a plain file of their own
   * extensions.
   *
   * @param configFile The path of the file, relative to the root of the archive holding it
   * @param configFilesOfTheSameArchive The paths of all workflow module configuration files of that archive
   * @param workflowModuleIds The IDs of all workflow modules found
   * @return The plain files which would make this one be read, or nothing where the file is
   *         read as it is
   */
  private static Optional<String> plainFileAProfileFileNeeds(
      final String configFile,
      final Collection<String> configFilesOfTheSameArchive,
      final Collection<String> workflowModuleIds) {

    final var lastSlash = configFile.lastIndexOf('/');
    final var directory = configFile.substring(0, lastSlash + 1);
    final var filename = configFile.substring(lastSlash + 1);
    final var lastDot = filename.lastIndexOf('.');
    final var filenameWithoutExtension = filename.substring(0, lastDot);
    final var extension = filename.substring(lastDot + 1);

    if (workflowModuleIds.contains(filenameWithoutExtension)) {
      // a file named after a workflow module and nothing else is a plain file itself.
      // This holds even where the ID of another module is the start of its name
      return Optional.empty();
    }
    // the longest ID wins: for the modules "loan" and "loan-approval" the file
    // "loan-approval-prod.yaml" belongs to the second one
    final var workflowModuleId = workflowModuleIds
        .stream()
        .filter(id -> filenameWithoutExtension.startsWith("%s-".formatted(id)))
        .max(Comparator.comparingInt(String::length));
    if (workflowModuleId.isEmpty()) {
      return Optional.empty();
    }

    final var plainFiles = fileExtensionsOfTheSameProvider(extension)
        .map(sameKind -> "%s%s.%s".formatted(directory, workflowModuleId.get(), sameKind))
        .toList();
    if (plainFiles
        .stream()
        .anyMatch(configFilesOfTheSameArchive::contains)) {
      return Optional.empty();
    }
    return Optional.of(plainFiles
        .stream()
        .map("'%s'"::formatted)
        .collect(Collectors.joining(" or ")));

  }

  /**
   * @param extension A file extension one of the two config source providers reads
   * @return The extensions of that provider, this one included
   */
  private static Stream<String> fileExtensionsOfTheSameProvider(
      final String extension) {

    return Stream
        .of(propertiesFileExtensions(), yamlFileExtensions())
        .filter(extensionsOfOneProvider -> Arrays
            .asList(extensionsOfOneProvider)
            .contains(extension))
        .flatMap(Arrays::stream);

  }

  /**
   * @return The file extensions the properties config source provider of a workflow module reads
   */
  private static String[] propertiesFileExtensions() {

    return new WorkflowModuleSpecificPropertiesConfigSourceProvider("any", -1)
        .getFileExtensions();

  }

  /**
   * @return The file extensions the YAML config source provider of a workflow module reads
   */
  private static String[] yamlFileExtensions() {

    return new WorkflowModuleSpecificYamlConfigSourceProvider("any", -1)
        .getFileExtensions();

  }

  /**
   * Builds the rule saying whether a file is one the config source providers of a workflow
   * module read: the file named after the workflow module ID and every profile-specific
   * variant of it, in each of the four directories of {@link WorkflowModuleConfigFiles#directoriesOf},
   * for each file extension both providers support.
   * <p>
   * It reads a path relative to the root of an archive or of a resource directory, which is
   * the form dev mode hands to a watched-file predicate and the form the archives are walked
   * in below.
   *
   * @param allWorkflowModules All workflow modules found
   * @return Whether a relative path is a configuration file of one of those workflow modules
   */
  private static Predicate<String> workflowModuleSpecificConfigFileRule(
      final VanillaBpWorkflowModulesBuildItem allWorkflowModules) {

    final var extensionPattern = Stream
        // join all possible file extensions into one "or" expression
        .concat(
            Arrays.stream(propertiesFileExtensions()),
            Arrays.stream(yamlFileExtensions()))
        .collect(Collectors.joining("|"));
    final var relativePathPatterns = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        // one regex for each workflow module. It matches "id[-profile].(extension1|extension2)"
        // in each of the places where that module may put a file
        .map(id -> "(?:%s)%s(?:-[^/]*)?\\.(?:%s)".formatted(
            WorkflowModuleConfigFiles
                .directoriesOf(id)
                .stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|")),
            Pattern.quote(id),
            extensionPattern))
        .map(Pattern::compile)
        .toList();

    return relativePath -> relativePathPatterns
        .stream()
        .anyMatch(pattern -> pattern.matcher(relativePath).matches());

  }

  /**
   * Searches all application archives for the configuration files of the workflow modules
   * found. A file is collected by its path relative to the archive root rather than by its
   * name, because the providers read four locations and a native image resource is named by
   * the path it is loaded under.
   *
   * @param applicationArchives The archives of this Quarkus build
   * @param isWorkflowModuleConfigFile Whether a relative path is a configuration file of a workflow module
   * @return The paths of those files relative to the root of the archive holding them,
   *         deduplicated because the same path may show up in more than one archive
   */
  private static SortedSet<String> workflowModuleSpecificConfigFiles(
      final ApplicationArchivesBuildItem applicationArchives,
      final Predicate<String> isWorkflowModuleConfigFile) {

    final var configFiles = new TreeSet<String>();
    workflowModuleSpecificConfigFilesPerArchive(applicationArchives, isWorkflowModuleConfigFile)
        .forEach(configFiles::addAll);
    return configFiles;

  }

  /**
   * The same search as {@link #workflowModuleSpecificConfigFiles(ApplicationArchivesBuildItem, Predicate)},
   * but it keeps the files of each archive apart. Whether a profile-specific file is read
   * depends on the archive it is in. The merged list cannot answer that question.
   *
   * @param applicationArchives The archives of this Quarkus build
   * @param isWorkflowModuleConfigFile Whether a relative path is a configuration file of a workflow module
   * @return One set of relative paths per archive, archives without such a file included
   */
  private static List<SortedSet<String>> workflowModuleSpecificConfigFilesPerArchive(
      final ApplicationArchivesBuildItem applicationArchives,
      final Predicate<String> isWorkflowModuleConfigFile) {

    final var configFilesPerArchive = new LinkedList<SortedSet<String>>();
    applicationArchives
        .getAllArchives()
        .forEach(archive -> {
          final var configFiles = new TreeSet<String>();
          archive
              .accept(openPathTree -> openPathTree
                  .walk(visit -> Optional
                      .ofNullable(visit.getRelativePath("/"))
                      .filter(isWorkflowModuleConfigFile)
                      .ifPresent(configFiles::add)));
          configFilesPerArchive.add(configFiles);
        });
    return configFilesPerArchive;

  }

  /**
   * Reads the ID of a workflow module out of its descriptor. The descriptor is the file
   * <code>META-INF/workflow-module</code> of an archive, and its whole content is the ID.
   * <p>
   * A descriptor which cannot be read ends the build. An archive which carries one is a
   * workflow module. If the build silently skipped it, its workflow services would move into
   * another module or end up without one.
   *
   * @param descriptorInJar Where the descriptor lies. It only works on the machine which runs
   *          the build
   * @return The ID, with the whitespace around it removed, or nothing where the file says
   *         nothing at all
   */
  public static Optional<String> readWorkflowModuleDescriptor(
      final URI descriptorInJar) {

    try (final var descriptor = descriptorInJar
        .toURL()
        .openStream()) {
      final var workflowModuleId = new String(descriptor.readAllBytes(), StandardCharsets.UTF_8);
      final var trimmedWorkflowModuleId = workflowModuleId.trim();
      if (trimmedWorkflowModuleId.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(trimmedWorkflowModuleId);

    } catch (IOException e) {
      throw new IllegalStateException(
          "Could not load workflow id from '%s'".formatted(descriptorInJar.toString()), e);
    }

  }

}
