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
 * A build item holding all workflow modules found during augmentation. It also carries
 * the assignment of {@link io.vanillabp.spi.service.WorkflowService} classes to workflow
 * modules, computed on demand and cached per build invocation (build items do not
 * survive dev-mode reloads, so no stale {@link ClassInfo} instances are leaked).
 */
public final class VanillaBpWorkflowModulesBuildItem extends SimpleBuildItem {

  /**
   * All workflow modules found during augmentation, keyed by the archive holding the
   * workflow-module descriptor. The same workflow module (equality is based on the
   * module's ID) may be provided by more than one archive (e.g. in tests where the
   * descriptor is part of the test archive and of the module's classpath directory).
   */
  private final Map<ApplicationArchive, WorkflowModule> workflowModulesByArchive;

  /**
   * Cache of service classes resolved to their workflow modules. Local to this build
   * item and therefore local to a single build invocation.
   */
  private final Map<DotName, WorkflowModule> resolvedWorkflowModules = new HashMap<>();

  public VanillaBpWorkflowModulesBuildItem(
      final Map<ApplicationArchive, WorkflowModule> workflowModulesByArchive) {

    this.workflowModulesByArchive = workflowModulesByArchive;

  }

  /**
   * @return All workflow modules found (workflow modules found in more than one archive
   *         are reported once since equality of workflow modules is based on their IDs)
   */
  public Set<WorkflowModule> getWorkflowModules() {

    return new LinkedHashSet<>(workflowModulesByArchive.values());

  }

  /**
   * Determines the workflow module ID for a given {@link io.vanillabp.spi.process.ProcessService} class.
   *
   * @param applicationArchivesBuildItem Information about all archives (JARs and directories) of the project
   * @param serviceClass The {@link io.vanillabp.spi.process.ProcessService} class
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

    // load workflow module ID from META-INF/workflow-module of the Java module JAR
    // the workflow service class belongs to
    // ==== NOT YET SUPPORTED ====
    /*
    if (serviceClass.module() != null) {
      serviceClass.module().moduleInfoClass()
      ....
    }
     */

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
              - in JAR/directory of Java module (%s) of class '%s'
              - in global classpath"""
            .formatted(
                WorkflowModule.METAINF_WORKFLOWMODULE,
                serviceClass.name(),
                serviceClass.module() == null ? "if defined" : serviceClass.module().name(),
                serviceClass.name()));

  }

}
