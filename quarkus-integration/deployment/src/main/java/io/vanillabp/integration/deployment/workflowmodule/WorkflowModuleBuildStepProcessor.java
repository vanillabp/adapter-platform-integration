package io.vanillabp.integration.deployment.workflowmodule;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.runtime.configuration.ConfigBuilder;
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
   * Priority of workflow-module-specific YAML config files. A tick more important than the original "application.yaml".
   *
   * @see WorkflowModuleBuildStepProcessor#buildWorkflowModuleSpecificConfigFilesConfigBuilder(List, VanillaBpWorkflowModulesBuildItem, BuildProducer)
   */
  public static final int YAML_CONFIGFILE_ORDINAL = 256;

  /**
   * Priority of workflow-module-specific Properties config files. A tick more important than the original "application.properties".
   *
   * @see WorkflowModuleBuildStepProcessor#buildWorkflowModuleSpecificConfigFilesConfigBuilder(List, VanillaBpWorkflowModulesBuildItem, BuildProducer)
   */
  public static final int PROPERTIES_CONFIGFILE_ORDINAL = 251;

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
        .getAllApplicationArchives()
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
   *       <td><b><code>XXX.yaml</code> (classpath, if <code>quarkus-config-yaml</code> is enabled)</b></td>
   *       <td><nobr><b>256</b></nobr></td>
   *       <td><b>YAML on the classpath specific to a certain workflow module having ID &quot;XXX&quot;.
   *       Lower priority than filesystem <i>application.yaml</i> since external files may be used to
   *       override values without the need for rebuilding and deploying the application.</b></td>
   *     </tr>
   *     <tr>
   *       <td><code>application.yaml</code> (classpath, if <code>quarkus-config-yaml</code> is enabled)</td>
   *       <td><nobr>255</nobr></td>
   *       <td>YAML on classpath; Quarkus gives YAML higher priority than classpath properties</td>
   *     </tr>
   *     <tr>
   *       <td><b><code>XXX.properties</code> (classpath, e.g. <code>src/main/resources</code>)</b></td>
   *       <td><nobr><b>251</b></nobr></td>
   *       <td><b>Properties on the classpath specific to a certain workflow module having ID &quot;XXX&quot;.
   *       Lower priority than filesystem <i>application.properties</i> since external files may be used to
   *       override values without the need for rebuilding and deploying the application.</b></td>
   *     </tr>
   *     <tr>
   *       <td><code>application.properties</code> (classpath, e.g. <code>src/main/resources</code>)</td>
   *       <td><nobr>250</nobr></td>
   *       <td>Default classpath application.properties</td>
   *     </tr>
   *     <tr>
   *       <td><code>META-INF/microprofile-config.properties</code> (classpath)</td>
   *       <td><nobr>100</nobr></td>
   *       <td>MicroProfile standard source (lowest practical priority)</td>
   *     </tr>
   *   </tbody>
   * </table>
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
   * @param staticInitConfigProducer Producer for static initialization config builders
   * @param runTimeConfigProducer Producer for static runtime config builders
   */
  @BuildStep
  WorkflowModuleSpecificConfigBuilderBuildItem addWorkflowModuleSpecificConfigFiles(
      final GeneratedConfigBuilderClassesBuildItem generatedConfigLoaders,
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
   * Watches module-specific configuration files for hot deployment in a workflow application.
   * This method identifies and monitors all possible configuration files (properties or YAML)
   * associated with workflow modules as defined in the build process, scanning them from the
   * application archives. Both locations loaded by the config source providers are watched:
   * files at the classpath root (<code>{id}[-{profile}].{ext}</code>) and files inside a
   * subdirectory named after the workflow module ID
   * (<code>{id}/{id}[-{profile}].{ext}</code>). Therefore, files are registered using their
   * path relative to the archive root, not just their file name.
   *
   * @param allWorkflowModules A {@link VanillaBpWorkflowModulesBuildItem} containing information about all workflow modules.
   * @param applicationArchives An {@link ApplicationArchivesBuildItem} containing the archives to be scanned for configuration files.
   * @param watchedFiles A {@link BuildProducer} that collects {@link HotDeploymentWatchedFileBuildItem} instances for hot deployment purposes.
   * @see WorkflowModuleSpecificPropertiesConfigSourceProvider#getConfigSources(ClassLoader)
   * @see WorkflowModuleSpecificYamlConfigSourceProvider#getConfigSources(ClassLoader)
   */
  @BuildStep
  void watchWorkflowModuleSpecificConfigFiles(
      final VanillaBpWorkflowModulesBuildItem allWorkflowModules,
      final ApplicationArchivesBuildItem applicationArchives,
      final BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles) {

    // determine all relative paths possible

    final var propertiesFileExtensions = new WorkflowModuleSpecificPropertiesConfigSourceProvider(
        "any", -1)
        .getFileExtensions();
    final var yamlFileExtensions = new WorkflowModuleSpecificYamlConfigSourceProvider(
        "any", -1)
        .getFileExtensions();

    final var extensionPattern = Stream
        // combine each file extension possible for later use as or-expression
        .concat(
            Arrays.stream(propertiesFileExtensions),
            Arrays.stream(yamlFileExtensions))
        .collect(Collectors.joining("|"));
    final var relativePathPatterns = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .map(Pattern::quote)
        // to build regex patterns matching "id[-profile].(extension1|extension2)" at the
        // classpath root as well as inside a subdirectory named after the workflow module ID
        .map(id -> "(?:%s/)?%s[^/]*\\.(?:%s)".formatted(id, id, extensionPattern))
        .map(Pattern::compile)
        .toList();

    // search archives for config files

    applicationArchives
        .getAllApplicationArchives()
        // traverse all archives
        .forEach(archive -> archive
            .accept(openPathTree -> openPathTree
                .walk(visit -> relativePathPatterns.
                // and check each file's relative path to match one of the patterns
                    forEach(pattern -> Optional
                        .ofNullable(visit.getRelativePath("/"))
                        .filter(relativePath -> pattern.matcher(relativePath).matches())
                        .map(HotDeploymentWatchedFileBuildItem::new)
                        .ifPresent(watchedFiles::produce)))));

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
