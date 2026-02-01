package io.vanillabp.integration.adapter.migration.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class MigrationAdapterProperties extends AdaptersConfigurationProperties {

  private static final Logger logger = LoggerFactory.getLogger(MigrationAdapterProperties.class);

  public static final String PREFIX = "vanillabp";

  /**
   * Map of all adapters available. Keys are the adapter IDs and the values are the adapter types.
   */
  @Builder.Default
  private Map<String, String> adapters = Map.of();

  /**
   * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
   */
  private String resourcesLocation;

  /**
   * Properties specific to workflow modules.
   */
  @Builder.Default
  private Map<String, WorkflowModuleAdapterProperties> workflowModules = Map.of();

  public void setWorkflowModules(
      final Map<String, WorkflowModuleAdapterProperties> workflowModules) {

    this.workflowModules = workflowModules;
    workflowModules.forEach((
        workflowModuleId,
        properties) -> properties.workflowModuleId = workflowModuleId);

  }

  public List<String> getPrioritizedAdaptersFor(
      final String workflowModuleId) {

    return getPrioritizedAdaptersFor(
        workflowModuleId,
        null);

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

  /**
   * Provides the resources location according to the given properties.
   * 
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @return Entry holding the location as a key and whether the location contains VanillaBP BPMN (true)
   *         or BPMN specific to the target BPMS (false).
   */
  public Map.Entry<String, Boolean> getAdapterResourcesLocationFor(
      final String workflowModuleId,
      final String adapterId) {

    var isVanillaBpmn = true;
    var resourcesLocation = getResourcesLocation();
    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule != null) {
      final var adapter = workflowModule.getAdapters().get(adapterId);
      if ((adapter != null) && (adapter.getResourcesLocation() != null) && !adapter.getResourcesLocation().isBlank()) {
        resourcesLocation = adapter.getResourcesLocation();
        isVanillaBpmn = false;
      }
    }

    if ((resourcesLocation == null) || resourcesLocation.isBlank()) {
      throw new IllegalStateException(
          """
              Neither property '%s.workflow-modules.%s.adapters.%s.resources-location' for resources specific to the BPMS
              nor property '%s.resources-location' for VanillaBP resources (not specific to the BPMS) is set!

              If using first option then the location needs to be specific to the adapter in order to avoid future
              problems once you wish to migrate to another adapter. Sample: 'classpath*:/workflow-resources/%s'"""
              .formatted(PREFIX, workflowModuleId, adapterId, PREFIX, adapterId));
    }
    return Map.entry(resourcesLocation, isVanillaBpmn);

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

    final var adaptersNotInClasspath = adapters
        .entrySet()
        .stream()
        .filter(entry -> !adaptersLoaded.contains(entry.getValue()))
        .map(entry -> "%s of type %s".formatted(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(",\n  "));
    if (!adaptersNotInClasspath.isEmpty()) {
      throw new IllegalStateException(
          """
              The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
                 %s
              Available adapter types in classpath: %s"""
              .formatted(adaptersNotInClasspath, adaptersLoaded));
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
      final var specificBpmnResources = workflowModules
          .keySet()
          .stream()
          .map(workflowModuleId -> Map.entry(workflowModuleId,
              getAdapterResourcesLocationFor(workflowModuleId, adapterId)))
          .filter(entry -> {
            final var isVanillaBpmn = entry.getValue().getValue();
            return !isVanillaBpmn;
          })
          .map(Map.Entry::getKey)
          .toList();
      if (!specificBpmnResources.isEmpty()) {
        final var propPrefix = "%s.workflow-modules.".formatted(PREFIX);
        final var propInfix = ".adapters.%s.resources-location\n  %s".formatted(
            adapterId,
            propPrefix);
        final var propPostfix = ".adapters.%s.resources-location".formatted(adapterId);
        logger.info(
            """
                Found only one VanillaBP adapter '%s' configured. Please ensure the properties
                  %s%s%s
                are specific to this adapter in order to avoid future-problems once you wish to migrate to another adapter."""
                .formatted(
                    adapterId,
                    propPrefix,
                    String.join(
                        propInfix,
                        specificBpmnResources),
                    propPostfix));
      }
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
