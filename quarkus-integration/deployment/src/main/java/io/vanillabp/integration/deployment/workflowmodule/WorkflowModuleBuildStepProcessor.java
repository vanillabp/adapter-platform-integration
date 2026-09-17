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
import io.vanillabp.integration.runtime.config.WorkflowModuleConfigFilesRecorder;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificPropertiesConfigBuilder;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificPropertiesConfigSourceProvider;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificYamlConfigBuilder;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificYamlConfigSourceProvider;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import lombok.extern.slf4j.Slf4j;

/**
 * VanillaBP extension build step processor, responsible for processing workflow modules.
 */
@Slf4j
public class WorkflowModuleBuildStepProcessor {

  /**
   * Priority of workflow-module-specific YAML config files. Below the classpath "application.yaml" (255),
   * because a workflow module ships defaults and the application always wins.
   * <p>
   * The distance to {@link #PROPERTIES_CONFIGFILE_ORDINAL} is the same 5 SmallRye keeps between
   * "application.yaml" and "application.properties", so YAML beats properties among a module's own
   * files just like it does among the application's. The distance to the application's weakest file
   * (250) leaves room for the bump SmallRye adds per active profile: a profile-specific file is
   * loaded at the ordinal of its base file plus one per profile
   * ({@code AbstractLocationConfigSourceLoader.ConfigurableProfileConfigSourceFactory}), so even a
   * dozen active profiles cannot lift a module file above "application.properties".
   *
   * @see WorkflowModuleBuildStepProcessor#buildWorkflowModuleSpecificConfigFilesConfigBuilder(List, VanillaBpWorkflowModulesBuildItem, BuildProducer)
   */
  public static final int YAML_CONFIGFILE_ORDINAL = 235;

  /**
   * Priority of workflow-module-specific Properties config files. Below the classpath
   * "application.properties" (250) and below {@link #YAML_CONFIGFILE_ORDINAL}, see there.
   *
   * @see WorkflowModuleBuildStepProcessor#buildWorkflowModuleSpecificConfigFilesConfigBuilder(List, VanillaBpWorkflowModulesBuildItem, BuildProducer)
   */
  public static final int PROPERTIES_CONFIGFILE_ORDINAL = 230;

  /**
   * Finds all workflow modules specified by <code>META-INF/workflow-module</code> files in their
   * Maven/Gradle module. The result is keyed by the archive holding the descriptor since the
   * same workflow module may be provided by more than one archive (workflow module equality
   * is based on the module's ID).
   *
   * @param applicationArchivesBuildItem The archives part of this Quarkus build
   * @return A build item holding meta-information of all workflow modules
   */
  @BuildStep
  VanillaBpWorkflowModulesBuildItem findAllWorkflowModules(
      final ApplicationArchivesBuildItem applicationArchivesBuildItem) {

    final var workflowModules = applicationArchivesBuildItem
        // search all archives of the project
        .getAllArchives()
        .stream()
        .flatMap(archive -> Optional
            // for META-INF/workflow-module files
            .ofNullable(archive.getChildPath(WorkflowModule.METAINF_WORKFLOWMODULE))
            .map(Path::toUri)
            .flatMap(sourceUri -> WorkflowModuleBuildStepProcessor
                // and read them
                .readWorkflowModuleDescriptor(sourceUri)
                // to build a WorkflowModule object
                .map(id -> Map.entry(archive, WorkflowModule
                    .builder()
                    .id(id)
                    // NOTE: sourceUri is resolvable on the BUILD machine / in dev
                    // mode only (it points into the augmentation classpath) -
                    // prod-mode code paths must never dereference it; it is kept
                    // to locate the workflow module (e.g. resources) at build time
                    .sourceUri(sourceUri)
                    .global(archive.equals(applicationArchivesBuildItem.getRootArchive()))
                    .build())))
            .stream())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    return new VanillaBpWorkflowModulesBuildItem(workflowModules);

  }

  /**
   * Builds {@link ConfigBuilder} classes responsible for loading properties from files specific to a workflow
   * module by using the workflow module's ID as a filename instead of <code>application.properties</code>.
   * Those files support modularization of workflow modules because next to code and BPMS resources (e.g.
   * BPMN files) also configuration can be placed in the same Maven/Gradle module.
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
   *       <td><nobr>400</nobr></td>
   *       <td>Highest priority (MicroProfile / SmallRye standard)</td>
   *     </tr>
   *     <tr>
   *       <td>Environment variables</td>
   *       <td><nobr>300</nobr></td>
   *       <td>Typical OS / container environment variables</td>
   *     </tr>
   *     <tr>
   *       <td><code>.env</code> file in the current working directory</td>
   *       <td><nobr>295</nobr></td>
   *       <td>Optional, if present Quarkus loads it automatically</td>
   *     </tr>
   *     <tr>
   *       <td><code>$PWD/config/application.yaml</code> (config directory, if <code>quarkus-config-yaml</code> is enabled)</td>
   *       <td><nobr>265</nobr></td>
   *       <td>Filesystem YAML has higher priority than classpath YAML</td>
   *     </tr>
   *     <tr>
   *       <td><code>$PWD/config/application.properties</code> (config directory next to runner)</td>
   *       <td><nobr>260</nobr></td>
   *       <td>External configuration file outside of the JAR</td>
   *     </tr>
   *     <tr>
   *       <td><code>application.yaml</code> (classpath, if <code>quarkus-config-yaml</code> is enabled)</td>
   *       <td><nobr>255</nobr></td>
   *       <td>YAML on classpath; Quarkus gives YAML higher priority than classpath properties</td>
   *     </tr>
   *     <tr>
   *       <td><code>application.properties</code> (classpath, e.g. <code>src/main/resources</code>)</td>
   *       <td><nobr>250</nobr></td>
   *       <td>Default classpath application.properties</td>
   *     </tr>
   *     <tr>
   *       <td><b><code>XXX.yaml</code> (classpath, if <code>quarkus-config-yaml</code> is enabled)</b></td>
   *       <td><nobr><b>235</b></nobr></td>
   *       <td><b>YAML on the classpath specific to a certain workflow module having ID &quot;XXX&quot;.
   *       A workflow module ships defaults, so everything the application configures wins over it -
   *       its classpath files as much as a file next to the runner.</b></td>
   *     </tr>
   *     <tr>
   *       <td><b><code>XXX.properties</code> (classpath, e.g. <code>src/main/resources</code>)</b></td>
   *       <td><nobr><b>230</b></nobr></td>
   *       <td><b>Properties on the classpath specific to a certain workflow module having ID &quot;XXX&quot;.
   *       Below the module's own YAML, mirroring the distance between the application's two files.</b></td>
   *     </tr>
   *     <tr>
   *       <td><code>META-INF/microprofile-config.properties</code> (classpath)</td>
   *       <td><nobr>100</nobr></td>
   *       <td>MicroProfile standard source (lowest practical priority)</td>
   *     </tr>
   *   </tbody>
   * </table>
   *
   * <p>Profiles are not in the table because they are not separate rows: SmallRye loads
   * <code>name-{profile}.ext</code> at the ordinal of <code>name.ext</code> raised by one per active
   * profile, so <code>XXX-prod.yaml</code> beats <code>XXX.yaml</code> and
   * <code>application-prod.yaml</code> beats <code>application.yaml</code>, each within its own band.
   * The 15 between 235 and 250 is what keeps the two bands apart.
   *
   * <p>The two module ordinals used to be 256 and 251, one tick ABOVE the application's
   * classpath files. A workflow module could therefore not be reconfigured from
   * <code>application.yaml</code>, which is the normal place to do it, and Spring Boot answered the
   * same question differently. Both platforms now agree: system properties, environment variables,
   * the application's configuration wherever it lives, <code>XXX-{profile}</code>, <code>XXX</code>.
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
   * Activate {@link ConfigBuilder} classes generated for workflow module specific files. This is
   * only done for custom workflow module properties because VanillaBP properties are needed already
   * during augmentation.
   * <p>
   * This is done in a separate step to ensure generated classes are available. If this had been done in
   * one build step, then there would have been no guarantees that the classes are available
   * when building the configuration. based on the {@link ConfigBuilder}.
   *
   * @param generatedConfigLoaders The build item holding the names of all {@link ConfigBuilder} classes
   * @param configFilesReported The report about the configuration files of the workflow
   *          modules which this application does not read, asked for here because a build step
   *          whose items nobody consumes is dropped, and the report has to be written in every
   *          build
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
   * Adds a {@link ConfigBuilder} class holding the workflow module IDs used to load files
   * next to the ordinal used to prioritize the builder next to other builders.
   * <pre>
   * package of.abstract.config.builder.class;
   * import java.util.List;
   * public class ActualNameOfAbstractConfigBuilderClass extends NameOfAbstractConfigBuilderClass {
   *     protected int getOrdinal() {
   *          return 4711;
   *     }
   *     protected List<String> getWorkflowModuleIds() {
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
   * Tells dev mode and the native image about the module-specific configuration files
   * (properties or YAML) of all workflow modules found. One rule says which names count, and
   * that is what keeps the two from drifting apart, but each of them needs it in a shape of
   * its own: dev mode gets the rule, the native image gets the files which match it in this
   * build.
   * <p>
   * Dev mode is asked with a predicate and not with a list of names, because a build writes
   * such a list once. A configuration file which a developer adds afterwards is in no list a
   * build produced, so watching the names of the last build would leave that file out of the
   * restart, and the application would go on running against the configuration of that build.
   * The predicate is as narrow as the names the config source providers read, so a YAML file
   * which has nothing to do with a workflow module restarts nothing.
   * <p>
   * A native image carries only the resources it was told about, and a file which is watched
   * in dev mode but missing from the image is the difference between a workflow module whose
   * settings apply and one whose settings are silently the defaults.
   * <p>
   * Dev mode watches a file in a <code>config</code> directory as well, although nothing
   * reads it. Adding one is the mistake this extension reports at startup, and without the
   * restart the developer sees neither the settings nor the report. The native image gets
   * only the files which are read.
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
    final var isInAConfigDirectory = workflowModuleConfigFileInAConfigDirectoryRule(allWorkflowModules);

    watchedFiles
        .produce(HotDeploymentWatchedFileBuildItem
            .builder()
            .setLocationPredicate(relativePath -> isWorkflowModuleConfigFile.test(relativePath) || isInAConfigDirectory
                .test(relativePath))
            .build());

    workflowModuleSpecificConfigFiles(applicationArchives, isWorkflowModuleConfigFile)
        .forEach(relativePath -> nativeImageResources.produce(new NativeImageResourceBuildItem(relativePath)));

  }

  /**
   * Reports the profile-specific configuration files of workflow modules which this
   * application does not read, and names the file each of them is missing.
   * <p>
   * SmallRye loads <code>id-{profile}.yaml</code> only where <code>id.yaml</code> lies in the
   * same place. It pairs the two so that the order the files are loaded in does not depend on
   * which resource a class loader answers with first
   * ({@code AbstractLocationConfigSourceLoader}). A workflow module which ships a file for one
   * profile and nothing else therefore ships settings nobody reads. Nothing said so until this
   * report: the application starts, and the first sign is a value which is not what the file
   * says. Spring Boot reads such a file, which is why a module author can build one
   * without ever meeting the rule (see decision 61 in the repository's DECISIONS.md).
   * <p>
   * The files are known while the application is built, the report is written when it starts:
   * a developer reads the log of a start far more often than the log of a build, and a native
   * binary says it as the JVM does.
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

    messageAboutFilesInAConfigDirectory(
        workflowModuleSpecificConfigFiles(
            applicationArchives,
            workflowModuleConfigFileInAConfigDirectoryRule(allWorkflowModules)))
        .ifPresent(recorder::report);

    return new WorkflowModuleConfigFilesReportedBuildItem();

  }

  /**
   * Builds the report about the files lying in a <code>config</code> directory. The archives
   * are asked as one list here, because where such a file lies is the whole answer and the
   * archive holding it changes nothing about it.
   *
   * @param configFilesInAConfigDirectory The paths of those files, relative to the archive roots
   * @return The warning, or nothing where no workflow module put a file there
   */
  static Optional<String> messageAboutFilesInAConfigDirectory(
      final Collection<String> configFilesInAConfigDirectory) {

    if (configFilesInAConfigDirectory.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of("""
        Configuration files of workflow modules which this application does not read:
          %s
        Quarkus reads a workflow module's configuration at the classpath root and in a \
        directory named after the workflow module. A 'config' directory is neither, so none \
        of the settings above arrive. Move each file to the place its line names. Spring \
        Boot searches a 'config' directory as well, so a workflow module built there arrives \
        here with its file in a place nobody looks at.""".formatted(
        configFilesInAConfigDirectory
            .stream()
            .map(configFile -> "%s, which belongs at '%s'".formatted(
                configFile,
                withoutTheConfigDirectory(configFile)))
            .collect(Collectors.joining("\n  "))));

  }

  /**
   * @param configFile The path of a file in a <code>config</code> directory
   * @return The same path with that directory taken out, which is where the file is read
   */
  private static String withoutTheConfigDirectory(
      final String configFile) {

    final var lastSlash = configFile.lastIndexOf('/');
    final var filename = configFile.substring(lastSlash + 1);
    // the rule matched the file, so its directory is "config" or "<module-id>/config"
    final var directory = configFile.substring(0, lastSlash);
    return directory.substring(0, directory.lastIndexOf('/') + 1) + filename;

  }

  /**
   * Builds the report about the profile-specific files which stay unread. Every archive is
   * asked on its own, because the file without the profile has to lie in the same archive and
   * the same directory to be found.
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
   * A file the extension of which belongs to the other provider is no help: the properties
   * provider and the YAML provider each pair a profile-specific file with a plain file of
   * their own extensions.
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
      // a file named after a workflow module and nothing else is a plain file itself,
      // also where the ID of another module starts its name
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
   * variant of it, at the classpath root as well as inside a subdirectory named after the ID,
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

    return configFileRule(allWorkflowModules, "(?:%s/)?");

  }

  /**
   * Builds the rule saying whether a file is named like the configuration of a workflow
   * module but lies in a <code>config</code> directory, which is a place the config source
   * providers do not read: <code>config/id.yaml</code> and
   * <code>id/config/id.yaml</code>, again with every profile-specific variant and every
   * file extension.
   * <p>
   * Spring Boot reads both of them, because it follows its own search for
   * <code>application.yaml</code>, which covers a <code>config</code> directory. Quarkus
   * has nothing of the kind for a file named after a workflow module. The rule exists so
   * that such a file can be named at startup rather than be missed
   * (see decision 65 in the repository's DECISIONS.md).
   *
   * @param allWorkflowModules All workflow modules found
   * @return Whether a relative path is such a file
   */
  private static Predicate<String> workflowModuleConfigFileInAConfigDirectoryRule(
      final VanillaBpWorkflowModulesBuildItem allWorkflowModules) {

    return configFileRule(allWorkflowModules, "(?:%s/)?config/");

  }

  /**
   * The shared shape of the two rules above: a file named after a workflow module, with an
   * optional profile and one of the extensions the providers read, in the directories the
   * given pattern allows.
   *
   * @param allWorkflowModules All workflow modules found
   * @param directoryPattern The regex for the directory part, taking the quoted module ID
   * @return Whether a relative path matches that shape for one of the workflow modules
   */
  private static Predicate<String> configFileRule(
      final VanillaBpWorkflowModulesBuildItem allWorkflowModules,
      final String directoryPattern) {

    final var extensionPattern = Stream
        // combine each file extension possible for later use as or-expression
        .concat(
            Arrays.stream(propertiesFileExtensions()),
            Arrays.stream(yamlFileExtensions()))
        .collect(Collectors.joining("|"));
    final var relativePathPatterns = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .map(Pattern::quote)
        // to build regex patterns matching "id[-profile].(extension1|extension2)" in the
        // directories the caller allows
        .map(id -> "%s%s(?:-[^/]*)?\\.(?:%s)".formatted(
            directoryPattern.formatted(id),
            id,
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
   * name, because the providers read two locations and a native image resource is named by
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
   * but keeping the files of each archive apart. Whether a profile-specific file is read
   * depends on the archive it sits in, so that question cannot be answered on the merged list.
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
        // traverse all archives
        .forEach(archive -> {
          final var configFiles = new TreeSet<String>();
          archive
              .accept(openPathTree -> openPathTree
                  // and check each file's relative path against the rule
                  .walk(visit -> Optional
                      .ofNullable(visit.getRelativePath("/"))
                      .filter(isWorkflowModuleConfigFile)
                      .ifPresent(configFiles::add)));
          configFilesPerArchive.add(configFiles);
        });
    return configFilesPerArchive;

  }

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
