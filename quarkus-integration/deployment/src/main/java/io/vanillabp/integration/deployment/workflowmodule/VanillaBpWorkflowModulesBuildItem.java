package io.vanillabp.integration.deployment.workflowmodule;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;

/**
 * A build item which holds all workflow modules found during augmentation. It also says
 * which workflow module each {@link io.vanillabp.spi.service.WorkflowService} class
 * belongs to. That is computed when asked and cached for one build. Build items do not
 * survive dev-mode reloads, so no outdated {@link ClassInfo} instances are kept.
 */
public final class VanillaBpWorkflowModulesBuildItem extends SimpleBuildItem {

  /**
   * All workflow modules found during augmentation, keyed by the archive which holds the
   * workflow-module descriptor. Two modules are equal if their IDs are equal. More than one
   * archive may provide the same workflow module. This happens e.g. in tests, where the
   * descriptor is part of the test archive and of the module's classpath directory.
   */
  private final Map<ApplicationArchive, WorkflowModule> workflowModulesByArchive;

  /**
   * Caches the workflow module of each service class. It belongs to this build item, so it
   * lives for one build only.
   */
  private final Map<DotName, WorkflowModule> resolvedWorkflowModules = new HashMap<>();

  /**
   * Built by {@link WorkflowModuleBuildStepProcessor#findAllWorkflowModules} once every
   * archive of the build was searched for a descriptor. Every step which needs to know the
   * workflow modules of this application reads it.
   *
   * @param workflowModulesByArchive The modules found, keyed by the archive their descriptor
   *          was found in. The item works on the map it is given and never changes it
   */
  public VanillaBpWorkflowModulesBuildItem(
      final Map<ApplicationArchive, WorkflowModule> workflowModulesByArchive) {

    this.workflowModulesByArchive = workflowModulesByArchive;

  }

  /**
   * The workflow modules of this application. Every call builds a new set, so a caller may
   * sort or filter the one it gets.
   *
   * @return All workflow modules found. A module found in more than one archive is in the set
   *         once, because two modules with the same ID are equal
   */
  public Set<WorkflowModule> getWorkflowModules() {

    return new LinkedHashSet<>(workflowModulesByArchive.values());

  }

  /**
   * Determines the workflow module ID for a given workflow service class, the class annotated
   * with {@code @WorkflowService}.
   *
   * @param applicationArchivesBuildItem Information about all archives (JARs and directories) of the project
   * @param serviceClass The workflow service class
   * @return The workflow module ID
   */
  public String getWorkflowModuleId(
      final ApplicationArchivesBuildItem applicationArchivesBuildItem,
      final ClassInfo serviceClass) {

    final var knownWorkflowModule = resolvedWorkflowModules.get(serviceClass.name());
    if (knownWorkflowModule != null) {
      return knownWorkflowModule.getId();
    }

    // load workflow module ID from META-INF/workflow-module of the same JAR
    // the workflow service class belongs to

    final var containingArchive = applicationArchivesBuildItem.containingArchive(serviceClass.name());
    final var workflowModuleInSameArchive = containingArchive == null
        ? null
        : workflowModulesByArchive.get(containingArchive);
    if (workflowModuleInSameArchive != null) {
      resolvedWorkflowModules.put(serviceClass.name(), workflowModuleInSameArchive);
      return workflowModuleInSameArchive.getId();
    }

    // load workflow module ID from META-INF/workflow-module in classpath
    // (this is suitable if the entire application is one workflow module):

    final var workflowModuleInRootArchive = workflowModulesByArchive
        .get(applicationArchivesBuildItem.getRootArchive());
    if (workflowModuleInRootArchive != null) {
      resolvedWorkflowModules.put(serviceClass.name(), workflowModuleInRootArchive);
      return workflowModuleInRootArchive.getId();
    }

    throw new IllegalStateException(
        """
            No workflow module descriptor '%s' was found in any valid location:
              - in JAR/directory of class '%s'
              - in global classpath"""
            .formatted(
                WorkflowModule.METAINF_WORKFLOWMODULE,
                serviceClass.name()));

  }

}
