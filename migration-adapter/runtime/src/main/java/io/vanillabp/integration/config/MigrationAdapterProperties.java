package io.vanillabp.integration.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class MigrationAdapterProperties extends AdapterProperties {

  private static final Logger logger = LoggerFactory.getLogger(MigrationAdapterProperties.class);

  public static final String PREFIX = "vanillabp";

  private Map<String, String> adapters = Map.of();

  private Map<String, WorkflowModuleAdapterProperties> workflowModules = Map.of();

  public void setWorkflowModules(
      final Map<String, WorkflowModuleAdapterProperties> workflowModules) {

    this.workflowModules = workflowModules;
    workflowModules.forEach(
        (
            workflowModuleId,
            properties) -> {
          properties.workflowModuleId = workflowModuleId;
        });

  }

  public List<String> getPrioritizedAdaptersFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    var prioritizedAdapters = getPrioritizedAdapters();
    if (workflowModuleId == null) {
      return prioritizedAdapters;
    }
    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule == null) {
      return prioritizedAdapters;
    }
    if (!workflowModule.getPrioritizedAdapters().isEmpty()) {
      prioritizedAdapters = workflowModule.getPrioritizedAdapters();
    }
    if (bpmnProcessId == null) {
      return prioritizedAdapters;
    }
    final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
    if (workflow == null) {
      return prioritizedAdapters;
    }
    if (!workflow.getPrioritizedAdapters().isEmpty()) {
      prioritizedAdapters = workflow.getPrioritizedAdapters();
    }
    return prioritizedAdapters;

  }

  public String getAdapterResourcesLocationFor(
      final String workflowModuleId,
      final String adapterId) {

    String resourcesLocation = null;
    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule != null) {
      final var adapter = workflowModule.getAdapters().get(adapterId);
      if (adapter != null) {
        resourcesLocation = adapter.getResourcesLocation();
      }
    }
    if (resourcesLocation == null) {
      throw new IllegalStateException(
          """
              Property '%s.workflow-modules.%s.adapters.%s.resources-location' not set!
              It has to point to a location specific to the adapter in order to avoid future problems once you wish to migrate to another adapter.
              Sample: 'classpath*:/workflow-resources/%s'"""
              .formatted(PREFIX, workflowModuleId, adapterId, adapterId));
    }
    return resourcesLocation;
  }

  public void validatePropertiesFor(
      final List<String> adapterIds,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var prioritizedAdapters = getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId);
    if (prioritizedAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              More than one VanillaBP adapter was configured, but no default adapter is configured at
                %s.workflow-modules.%s.workflows.%s.prioritized-adapters or
                %s.workflow-modules.%s.prioritized-adapters or
                %s.prioritized-adapters
              Available adapters are '%s'."""
              .formatted(PREFIX, workflowModuleId, bpmnProcessId, PREFIX, workflowModuleId, PREFIX, String
                  .join("', '", adapterIds)));
    }

    final var listOfAdapters = String.join("', '", adapterIds);
    final var missingAdapters = prioritizedAdapters.stream()
        .filter(prioritizedAdapter -> !adapterIds.contains(prioritizedAdapter))
        .collect(Collectors.joining("', '"));
    if (!missingAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              Property 'prioritized-adapters' of workflow-module '%s' and bpmn-process-id '%s' contains adapters not configured in 'vanillabp.adapters.*':
                %s
              Available adapters are: '%s'!"""
              .formatted(workflowModuleId, bpmnProcessId, missingAdapters, listOfAdapters));
    }
  }

  public void validateProperties(
      final List<String> adaptersLoaded,
      final List<String> knownWorkflowModuleIds) {

    if (knownWorkflowModuleIds.isEmpty()) {
      throw new IllegalStateException("No workflow-modules where given!");
    }

    final var adapterTypesNotInClasspath = adapters
        .entrySet()
        .stream()
        .filter(entry -> !adaptersLoaded.contains(entry.getValue()))
        .map(entry -> "%s of type %s".formatted(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(",\n  "));
    if (!adapterTypesNotInClasspath.isEmpty()) {
      throw new IllegalStateException(
          """
              The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
                 %s
              Available adapter types in classpath: %s"""
              .formatted(adapterTypesNotInClasspath, adaptersLoaded));
    }

    // unknown workflow-module properties
    final var workflowModulesConfiguredButNotInClasspath = new LinkedList<>(getWorkflowModules().keySet());
    workflowModulesConfiguredButNotInClasspath.removeAll(knownWorkflowModuleIds);
    if (!workflowModulesConfiguredButNotInClasspath.isEmpty()) {
      final var propPrefix = "\n  %s.workflow-modules.".formatted(PREFIX);
      logger.warn(
          """
              Found properties for workflow modules
                {}.workflow-modules.{}
              which were not found in the class-path!""",
          PREFIX, String.join(propPrefix, workflowModulesConfiguredButNotInClasspath));
    }

    // adapter configured
    if (adapters.size() == 1) {
      final var adapterId = adapters.keySet().iterator().next();
      final var propPrefix = "%s.workflow-modules.".formatted(PREFIX);
      final var propInfix = ".adapters.%s.resources-location\n  %s".formatted(adapterId, propPrefix);
      final var propPostfix = ".adapters.%s.resources-location".formatted(adapterId);
      logger.info(
          """
              Found only one VanillaBP adapter '%s' configured. Please ensure the properties
                %s%s%s
              are specific to this adapter in order to avoid future-problems once you wish to migrate to another adapter."""
              .formatted(adapterId, propPrefix, String.join(propInfix, workflowModules.keySet()),
                  propPostfix));
    }

    // adapters in class-path not used
    final var notConfiguredAdapters = new HashMap<String, Set<String>>() {
      @Override
      public Set<String> get(
          final Object key) {
        var adapters = super.get(key);
        if (adapters == null) {
          adapters = new HashSet<>();
          super.put(key.toString(), adapters);
        }
        return adapters;
      }
    };
    getPrioritizedAdapters()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .forEach(
            adapterId -> notConfiguredAdapters
                .get("%s.prioritized-adapters".formatted(MigrationAdapterProperties.PREFIX))
                .add(adapterId));
    getWorkflowModules()
        .values()
        .forEach(
            workflowModule -> {
              workflowModule.getPrioritizedAdapters().stream()
                  .filter(adapterId -> !adapters.containsKey(adapterId))
                  .forEach(
                      adapterId -> notConfiguredAdapters
                          .get("%s.workflow-modules.%s.prioritized-adapters".formatted(PREFIX,
                              workflowModule.workflowModuleId))
                          .add(adapterId));
              workflowModule
                  .getWorkflows()
                  .values()
                  .forEach(
                      workflow -> workflow
                          .getPrioritizedAdapters()
                          .stream()
                          .filter(adapterId -> !adapters.containsKey(adapterId))
                          .forEach(
                              adapterId -> notConfiguredAdapters
                                  .get("%s.workflow-modules.%s.workflows.%s.prioritized-adapters".formatted(PREFIX,
                                      workflowModule.workflowModuleId, workflow.getBpmnProcessId()))
                                  .add(adapterId)));
            });
    if (!notConfiguredAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
                %s
              """
              .formatted(notConfiguredAdapters
                  .entrySet()
                  .stream()
                  .map(entry -> "%s => %s".formatted(entry.getKey(), String.join(",", entry.getValue())))
                  .collect(Collectors.joining("\n  "))));
    }

    // resources-location
    knownWorkflowModuleIds.forEach(
        workflowModuleId -> {
          final var prioritizedAdaptersOfModule = getPrioritizedAdaptersFor(workflowModuleId, null);
          if (prioritizedAdaptersOfModule.isEmpty()) {
            throw new IllegalStateException("""
                You need to define at least one property of
                  %s.prioritized-adapters
                  %s.workflow-modules.%s.prioritized-adapters
                """
                .formatted(PREFIX, PREFIX, workflowModuleId));
          }
          prioritizedAdaptersOfModule.forEach(adapterId -> getAdapterResourcesLocationFor(workflowModuleId, adapterId));
          getBpmnProcessIdsForWorkflowModule(workflowModuleId)
              .forEach(
                  bpmnProcessId -> {
                    final var prioritizedAdapters = getPrioritizedAdaptersFor(
                        workflowModuleId,
                        bpmnProcessId
                    );
                    prioritizedAdapters.forEach(adapterId -> getAdapterResourcesLocationFor(
                        workflowModuleId,
                        adapterId));
                  });
        });
  }

  private List<String> getBpmnProcessIdsForWorkflowModule(
      final String workflowModuleId) {

    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule == null) {
      return List.of();
    }

    return workflowModule.getWorkflows().values().stream()
        .map(WorkflowAdapterProperties::getBpmnProcessId)
        .toList();
  }

}
