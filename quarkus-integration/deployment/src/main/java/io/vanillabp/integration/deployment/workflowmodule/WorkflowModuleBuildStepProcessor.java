package io.vanillabp.integration.deployment.workflowmodule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.jboss.jandex.ClassInfo;

import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import lombok.extern.slf4j.Slf4j;

/**
 * VanillaBP extension build step processor, responsible for processing workflow modules.
 */
@Slf4j
public class WorkflowModuleBuildStepProcessor {

  /**
   * The location of workflow module definition files.
   */
  private static final String METAINF_WORKFLOWMODULE = "META-INF/workflow-module";

  /**
   * Cache to accelerate augmentation phase.
   */
  private static final Map<ClassInfo, String> resolvedWorkflowModuleIds = new HashMap<>();

  /**
   * Determines the workflow module ID for a given {@link io.vanillabp.spi.process.ProcessService} class.
   *
   * @param applicationArchivesBuildItem Information about All archives (JARs and directories) of the project
   * @param serviceClass The {@link io.vanillabp.spi.process.ProcessService} class
   * @return The workflow module ID
   */
  public static String getWorkflowModuleId(
      final ApplicationArchivesBuildItem applicationArchivesBuildItem,
      final ClassInfo serviceClass) {

    final var knownWorkflowModuleId = resolvedWorkflowModuleIds.get(serviceClass);
    if (knownWorkflowModuleId != null) {
      return knownWorkflowModuleId;
    }

    // load workflow module ID from META-INF/workflow-module of the same JAR
    // the workflow service class belongs to
    loadWorkflowModuleIdFromApplicationArchive(
        serviceClass,
        applicationArchivesBuildItem.containingArchive(serviceClass.name()));
    final var jarWorkflowModuleId = resolvedWorkflowModuleIds.get(serviceClass);
    if (jarWorkflowModuleId != null) {
      log.info("Found VanillaBP workflow module with id '{}' in module containing class '{}'",
          jarWorkflowModuleId, serviceClass.name());
      return jarWorkflowModuleId;
    }

    // load workflow module ID from META-INF/workflow-module of the Java module JAR
    // the workflow service class belongs to
    // ==== NOT YET SUPPORTED ====
    /*
    if (serviceClass.module() != null) {
      loadWorkflowModuleIdFromApplicationArchive(
          serviceClass,
          applicationArchivesBuildItem.containingArchive(serviceClass.module().moduleInfoClass().name()));
    }
    final var moduleWorkflowModuleId = resolvedWorkflowModuleIds.get(serviceClass);
    if (moduleWorkflowModuleId != null) {
      log.info("Found VanillaBP workflow module with id '{}' in Java-module of class '{}'",
          moduleWorkflowModuleId, serviceClass.name());
      return moduleWorkflowModuleId;
    }
     */

    // load workflow module ID from META-INF/workflow-module in classpath
    // (this is suitable if the entire application is one workflow module):
    loadWorkflowModuleIdFromApplicationArchive(
        serviceClass,
        applicationArchivesBuildItem.getRootArchive());
    final var rootWorkflowModuleId = resolvedWorkflowModuleIds.get(serviceClass);
    if (rootWorkflowModuleId != null) {
      log.info("Found VanillaBP workflow module with id '{}' in Quarkus root module",
          rootWorkflowModuleId);
      return rootWorkflowModuleId;
    }

    throw new IllegalStateException(
        """
            No workflow module descriptor '%s' was found in any valid location:
              - in JAR/directory of class '%s'
              - in JAR/directory of Java module (%s) of class '%s'
              - in global classpath"""
            .formatted(
                METAINF_WORKFLOWMODULE,
                serviceClass.name(),
                serviceClass.module() == null ? "if defined" : serviceClass.module().name(),
                serviceClass.name()));

  }

  private static void loadWorkflowModuleIdFromApplicationArchive(
      final ClassInfo serviceClass,
      final ApplicationArchive applicationArchive) {

    final var descriptorInJar = applicationArchive
        .getChildPath("META-INF/workflow-module");
    if (descriptorInJar == null) {
      return;
    }
    try (final var descriptor = descriptorInJar
        .toUri()
        .toURL()
        .openStream()) {
      final var workflowModuleId = new String(descriptor.readAllBytes(), StandardCharsets.UTF_8);
      final var trimmedWorkflowModuleId = workflowModuleId.trim();
      if (trimmedWorkflowModuleId.isEmpty()) {
        return;
      }
      resolvedWorkflowModuleIds.put(serviceClass, trimmedWorkflowModuleId);
    } catch (IOException e) {
      throw new RuntimeException(
          "Could not load workflow id from '"
              + applicationArchive.getKey()
              + "'", e);
    }

  }

}
