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
import lombok.Setter;

@Getter
@Setter
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
          properties.defaultProperties = this;
        });

  }

  public List<String> getPrioritizedAdaptersFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    var prioritizedAdapters = getPrioritizedAdapters();
    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule != null) {
      if (!workflowModule.getPrioritizedAdapters().isEmpty()) {
        prioritizedAdapters = workflowModule.getPrioritizedAdapters();
      }
      if (bpmnProcessId != null) {
        final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
        if (workflow != null) {
          if (!workflow.getPrioritizedAdapters().isEmpty()) {
            prioritizedAdapters = workflowModule.getPrioritizedAdapters();
          }
        }
      }
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
      throw new RuntimeException(
          "Property '"
              + MigrationAdapterProperties.PREFIX
              + ".workflow-modules."
              + workflowModuleId
              + ".adapters."
              + adapterId
              + ".resources-location' not set!\nIt has to point to a location specific to the adapter in order "
              + "to avoid future problems once you wish to migrate to another adapter.\nSample: '"
              + "classpath*:/workflow-resources/"
              + adapterId
              + "'");
    }
    return resourcesLocation;
  }

  public void validatePropertiesFor(
      final List<String> adapterIds,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var prioritizedAdapters = getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId);
    if (prioritizedAdapters.isEmpty()) {
      throw new RuntimeException(
          "More than one VanillaBP adapter was found in classpath, but no default adapter is configured at\n  "
              + PREFIX
              + ".workflow-modules."
              + workflowModuleId
              + ".workflows."
              + bpmnProcessId
              + ".prioritized-adapters or \n  "
              + PREFIX
              + ".workflow-modules."
              + workflowModuleId
              + ".prioritized-adapters or \n  "
              + PREFIX
              + ".prioritized-adapters\nAvailable adapters are '"
              + String
                  .join("', '", adapterIds)
              + "'.");
    }

    final var listOfAdapters = String.join("', '", adapterIds);
    final var missingAdapters = prioritizedAdapters.stream()
        .filter(prioritizedAdapter -> !adapterIds.contains(prioritizedAdapter))
        .collect(Collectors.joining("', '"));
    if (!missingAdapters.isEmpty()) {
      throw new RuntimeException(
          "Property 'prioritized-adapters' of workflow-module '"
              + workflowModuleId
              + "' and bpmn-process-id '"
              + bpmnProcessId
              + "' contains adapters not available in classpath:\n'  "
              + missingAdapters
              + "'!\nAvailable adapters are: '"
              + listOfAdapters
              + "'.");
    }
  }

  public void validateProperties(
      final List<String> adaptersLoaded,
      final List<String> knownWorkflowModuleIds) {

    final var adaptersNotInClasspath = adapters
        .entrySet()
        .stream()
        .filter(entry -> !adaptersLoaded.contains(entry.getValue()))
        .map(entry -> "%s of type %s".formatted(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(",\n  "));
    if (!adaptersNotInClasspath.isEmpty()) {
      throw new IllegalStateException(
          "The following adapters were configured in properties section 'vanillabp.adapters' but the is no "
              + "adapter in classpath is matching the given type:\n  %s\nAvailable adapters in classpath: %s"
                  .formatted(adaptersNotInClasspath, adaptersLoaded));
    }

    // unknown workflow-module properties
    final var configAvailableButNotInClasspath = new LinkedList<>(getWorkflowModules().keySet());
    configAvailableButNotInClasspath.removeAll(knownWorkflowModuleIds);
    if (!configAvailableButNotInClasspath.isEmpty()) {
      logger.warn(
          "Found properties for workflow-modules\n"
              + PREFIX
              + ".workflow-modules."
              + String.join(
                  "\n"
                      + PREFIX
                      + ".workflow-modules.",
                  configAvailableButNotInClasspath
              )
              + "\nwhich were not found in the class-path!");
    }

    // adapter configured
    if (adaptersLoaded.size() == 1) {
      logger.info(
          "Found only one VanillaBP adapter '"
              + adaptersLoaded.getFirst()
              + "' in classpath. Please ensure the properties\n  "
              + PREFIX
              + ".workflow-modules"
              + String
                  .join(
                      ".adapters.*.resources-location\n"
                          + PREFIX
                          + ".workflow-modules",
                      configAvailableButNotInClasspath
                  )
              + ".adapters."
              + adaptersLoaded.getFirst()
              + ".resources-location\nare specific to this adapter in "
              + "order to avoid future-problems once you wish to migrate to another adapter.");
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
                .get(MigrationAdapterProperties.PREFIX
                    + ".prioritized-adapters")
                .add(adapterId));
    getWorkflowModules()
        .values()
        .forEach(
            workflowModule -> {
              workflowModule.getPrioritizedAdapters().stream()
                  .filter(adapterId -> !adapters.containsKey(adapterId))
                  .forEach(
                      adapterId -> notConfiguredAdapters
                          .get(
                              MigrationAdapterProperties.PREFIX
                                  + ".workflow-modules."
                                  + workflowModule.workflowModuleId
                                  + ".prioritized-adapters")
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
                                  .get(
                                      MigrationAdapterProperties.PREFIX
                                          + ".workflow-modules."
                                          + workflowModule.workflowModuleId
                                          + ".workflows."
                                          + workflow
                                              .getBpmnProcessId()
                                          + ".prioritized-adapters")
                                  .add(adapterId)));
            });
    if (!notConfiguredAdapters.isEmpty()) {
      throw new RuntimeException(
          "There are VanillaBP adapters references not found in properties section 'vanillabp.adapters.*':\n"
              + notConfiguredAdapters
                  .entrySet().stream()
                  .map(
                      entry -> "  "
                          + entry.getKey()
                          + "="
                          + String.join(",", entry.getValue()))
                  .collect(Collectors.joining("\n")));
    }

    // resources-location
    knownWorkflowModuleIds.forEach(
        workflowModuleId -> {
          final var prioritizedAdaptersOfModule = getPrioritizedAdaptersFor(workflowModuleId, null);
          final var resourcesLocationOfModule = (prioritizedAdaptersOfModule.isEmpty() ? getPrioritizedAdapters()
              : prioritizedAdaptersOfModule)
              .stream()
              .filter(
                  adapterId -> getAdapterResourcesLocationFor(workflowModuleId, adapterId) == null)
              .toList();
          if (!resourcesLocationOfModule.isEmpty()) {
            throw new RuntimeException(
                "You need to define properties '"
                    + PREFIX
                    + ".workflow-modules."
                    + workflowModuleId
                    + ".adapters."
                    + String
                        .join(
                            ".resources-location\n"
                                + PREFIX
                                + ".workflow-modules."
                                + workflowModuleId
                                + ".adapters.",
                            getPrioritizedAdapters()
                        )
                    + ".resources-location");
          }
          getBpmnProcessIdsForWorkflowModule(workflowModuleId)
              .forEach(
                  bpmnProcessId -> {
                    final var prioritizedAdapters = getPrioritizedAdaptersFor(
                        workflowModuleId,
                        bpmnProcessId
                    );
                    final var resourcesLocation = prioritizedAdapters.stream()
                        .filter(
                            adapterId -> getAdapterResourcesLocationFor(
                                workflowModuleId,
                                adapterId
                            ) == null)
                        .toList();
                    if (!resourcesLocation.isEmpty()) {
                      throw new RuntimeException(
                          "You need to define properties '"
                              + PREFIX
                              + ".workflow-modules."
                              + workflowModuleId
                              + ".adapters."
                              + String
                                  .join(
                                      ".resources-location\n"
                                          + PREFIX
                                          + ".workflow-modules."
                                          + workflowModuleId
                                          + ".adapters.",
                                      prioritizedAdapters
                                  )
                              + ".resources-location");
                    }
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
