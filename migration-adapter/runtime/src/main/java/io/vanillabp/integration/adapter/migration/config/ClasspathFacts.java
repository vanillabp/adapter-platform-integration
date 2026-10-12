package io.vanillabp.integration.adapter.migration.config;

import java.util.List;

/**
 * What the platform knows about the application WITHOUT reading any <code>vanillabp.*</code>
 * property: which adapter types the classpath provides, and which workflow modules it
 * contains. Convention over configuration is based on these facts.
 * {@link MigrationAdapterProperties#normalize(ClasspathFacts)} derives everything which can
 * be derived from them, BEFORE the checks run. The rules of the checks stay the same, and
 * they apply to derived entries exactly like to hand-written ones.
 * <p>
 * Decision 8 in the repository's DECISIONS.md says why these facts are read before the
 * configuration is checked.
 *
 * @param adapterTypes The adapter types found in the classpath (Spring Boot: the
 *          <code>AdapterConfigurationBase</code> beans, Quarkus: the VanillaBP
 *          adapter extensions' capabilities)
 * @param workflowModules The workflow modules found in the classpath
 */
public record ClasspathFacts(
                             List<String> adapterTypes,
                             List<WorkflowModuleInfo> workflowModules) {

  /**
   * A workflow module found in the classpath.
   *
   * @param id The workflow module's ID (content of its
   *          <code>META-INF/workflow-module</code> descriptor)
   * @param fromMainArtifact Whether the descriptor comes from the application's
   *          MAIN artifact (Spring Boot: the classpath root of the
   *          <code>&#64;SpringBootApplication</code> class; Quarkus: the root
   *          application archive) instead of a dependency. Only relevant for the
   *          resources-location convention: an application which IS the workflow
   *          module keeps its BPMN below <code>processes/</code>, whereas a
   *          workflow module shipped as its own artifact namespaces them below
   *          its module ID (see
   *          {@link MigrationAdapterProperties#getAdapterResourcesLocationsFor(String, String)}).
   */
  public record WorkflowModuleInfo(
                                   String id,
                                   boolean fromMainArtifact) {
  }

  /**
   * The facts of a platform which cannot tell where a workflow module descriptor came from.
   * Every module counts as an artifact of its own. That is the careful choice: its resources
   * are expected below the module ID.
   *
   * @param adapterTypes The adapter types found in the classpath
   * @param workflowModuleIds The workflow module IDs found in the classpath
   * @return The facts
   */
  public static ClasspathFacts of(
      final List<String> adapterTypes,
      final List<String> workflowModuleIds) {

    return new ClasspathFacts(
        adapterTypes, workflowModuleIds
            .stream()
            .map(id -> new WorkflowModuleInfo(id, false))
            .toList());

  }

  /**
   * The module ids alone, for everything which asks whether a configured module exists at
   * all, and does not care where its descriptor came from.
   *
   * @return The IDs of the workflow modules found in the classpath, in the order the
   *         platform read them
   */
  public List<String> workflowModuleIds() {

    return workflowModules
        .stream()
        .map(WorkflowModuleInfo::id)
        .toList();

  }

}
