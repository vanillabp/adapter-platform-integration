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
   * The location BPMN resources are loaded from and whether that location contains
   * VanillaBP BPMN (true) or BPMN specific to the target BPMS (false).
   *
   * @param location The resources location
   * @param vanillaBpBpmn Whether the location contains VanillaBP BPMN
   */
  public record ResourcesLocation(String location, boolean vanillaBpBpmn) {
  }

  /**
   * Map of all adapters available. Keys are the adapter IDs and the values are the adapter types.
   */
  @Builder.Default
  private Map<String, String> adapters = Map.of();

  /**
   * Per-adapter policy how to treat a failing deployment of BPMS resources
   * (property <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>).
   * Keys are the adapter IDs. Adapters not contained default to
   * {@link DeploymentFailurePolicy#FAIL}.
   */
  @Builder.Default
  private Map<String, DeploymentFailurePolicy> deploymentFailures = Map.of();

  /**
   * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
   */
  private String resourcesLocation;

  /**
   * Properties specific to workflow modules.
   */
  @Builder.Default
  private Map<String, WorkflowModuleAdapterProperties> workflowModules = Map.of();

  /**
   * Links child properties back to their parents (e.g. the workflow module ID into
   * the module's properties object). Invoked by {@link #validateProperties(List, List)};
   * has to be invoked explicitly if properties objects are built without running
   * validation (e.g. in tests).
   */
  public void validateAndLink() {

    workflowModules.forEach((
        workflowModuleId,
        moduleProperties) -> {
      moduleProperties.workflowModuleId = workflowModuleId;
      moduleProperties
          .getWorkflows()
          .forEach((
              bpmnProcessId,
              workflowProperties) -> {
            workflowProperties.bpmnProcessId = bpmnProcessId;
            workflowProperties.workflowModule = moduleProperties;
          });
    });

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
   * Provides the effective resilience settings, resolved on the same three override
   * levels as <code>prioritized-adapters</code>: global, workflow module and
   * workflow - the most specific block configured wins as a whole. Values not set
   * within the winning block fall back to the defaults.
   *
   * @param workflowModuleId The workflow module ID (may be null)
   * @param bpmnProcessId The BPMN process ID (may be null)
   * @return The effective resilience settings (never null, all values populated)
   */
  public ResilienceProperties getResilienceFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    var resilience = getResilience();
    if (workflowModuleId == null) {
      return ResilienceProperties.effective(resilience);
    }
    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule == null) {
      return ResilienceProperties.effective(resilience);
    }
    if (workflowModule.getResilience() != null) {
      resilience = workflowModule.getResilience();
    }
    if (bpmnProcessId == null) {
      return ResilienceProperties.effective(resilience);
    }
    final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
    if (workflow == null) {
      return ResilienceProperties.effective(resilience);
    }
    if (workflow.getResilience() != null) {
      resilience = workflow.getResilience();
    }
    return ResilienceProperties.effective(resilience);

  }

  /**
   * Provides the policy how to treat a failing deployment of BPMS resources for the
   * given adapter.
   *
   * @param adapterId The adapter ID
   * @return The policy, defaulting to {@link DeploymentFailurePolicy#FAIL}
   */
  public DeploymentFailurePolicy getDeploymentFailureFor(
      final String adapterId) {

    final var policy = deploymentFailures.get(adapterId);
    return policy != null
        ? policy
        : DeploymentFailurePolicy.FAIL;

  }

  /**
   * Provides the resources location according to the given properties.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @return The location and whether the location contains VanillaBP BPMN (true)
   *         or BPMN specific to the target BPMS (false).
   */
  public ResourcesLocation getAdapterResourcesLocationFor(
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
    return new ResourcesLocation(resourcesLocation, isVanillaBpmn);

  }

  public void validatePropertiesFor(
      final List<String> adapterIds,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var prioritizedAdapters = getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId);
    if (prioritizedAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              No adapter is configured to be used for BPMN process '%s' of workflow module '%s'! Define at least one of these properties:
                %s.workflow-modules.%s.workflows.%s.prioritized-adapters or
                %s.workflow-modules.%s.prioritized-adapters or
                %s.prioritized-adapters
              Available adapters are '%s'."""
              .formatted(bpmnProcessId, workflowModuleId, PREFIX, workflowModuleId, bpmnProcessId, PREFIX,
                  workflowModuleId, PREFIX, String
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

    validateAndLink();

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

    // deployment-failure policies have to be configured for known adapters only
    final var deploymentFailuresForUnknownAdapters = deploymentFailures
        .keySet()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .map(adapterId -> "%s.adapters.%s.deployment-failure".formatted(PREFIX, adapterId))
        .sorted()
        .collect(Collectors.joining("\n  "));
    if (!deploymentFailuresForUnknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              These properties refer to adapters not configured in 'vanillabp.adapters.*':
                %s"""
              .formatted(deploymentFailuresForUnknownAdapters));
    }

    // resilience blocks configured have to contain valid values
    if (getResilience() != null) {
      getResilience().validate("%s.resilience".formatted(PREFIX));
    }
    getWorkflowModules()
        .values()
        .forEach(workflowModule -> {
          if (workflowModule.getResilience() != null) {
            workflowModule
                .getResilience()
                .validate("%s.workflow-modules.%s.resilience"
                    .formatted(PREFIX, workflowModule.workflowModuleId));
          }
          workflowModule
              .getWorkflows()
              .values()
              .forEach(workflow -> {
                if (workflow.getResilience() != null) {
                  workflow
                      .getResilience()
                      .validate("%s.workflow-modules.%s.workflows.%s.resilience"
                          .formatted(PREFIX, workflowModule.workflowModuleId, workflow.getBpmnProcessId()));
                }
              });
        });

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
          .filter(entry -> !entry.getValue().vanillaBpBpmn())
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
