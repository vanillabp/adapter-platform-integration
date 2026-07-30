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
   * The configuration of all adapters known
   * (properties section <code>vanillabp.adapters.&lt;id&gt;.*</code>). Keys are the
   * adapter IDs. The key can be an adapter's type or a custom identifier. In case of
   * a custom identifier the {@link AdapterConfigProperties#getType() type} property
   * has to point to the adapter type the custom adapter is derived from. In case of
   * an adapter-type identifier the type property may be undefined.
   */
  @Builder.Default
  private Map<String, AdapterConfigProperties> adapters = Map.of();

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
   * Configuration of the default
   * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} implementations
   * (properties section <code>vanillabp.outbox</code>).
   */
  @Builder.Default
  private PhaseTwoOutboxProperties outbox = new PhaseTwoOutboxProperties();

  /**
   * Derived view of {@link #getAdapters()}: adapter ID mapped to the adapter's type.
   * An adapter entry without an explicit {@link AdapterConfigProperties#getType()
   * type} defaults to its ID being the type.
   *
   * @return Map of all adapters available (key = adapter ID, value = adapter type)
   */
  public Map<String, String> adapterTypes() {

    return adapters
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> entry.getValue().getType() != null
                ? entry.getValue().getType()
                : entry.getKey()));

  }

  /**
   * Applies convention-over-configuration defaults to the bound properties:
   * if exactly one adapter is configured, the property
   * <code>vanillabp.prioritized-adapters</code> may be omitted and defaults to that
   * adapter. Invoked by {@link #validateProperties(List, List)}; has to be invoked
   * explicitly if properties objects are used without running validation.
   */
  public void normalize() {

    if (getPrioritizedAdapters().isEmpty() && (adapters.size() == 1)) {
      setPrioritizedAdapters(List.copyOf(adapters.keySet()));
    }

  }

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
   * Provides the policy how to treat a failing deployment of BPMS resources for the
   * given adapter.
   *
   * @param adapterId The adapter ID
   * @return The policy, defaulting to {@link DeploymentFailurePolicy#FAIL}
   */
  public DeploymentFailurePolicy getDeploymentFailureFor(
      final String adapterId) {

    final var adapter = adapters.get(adapterId);
    return (adapter != null) && (adapter.getDeploymentFailure() != null)
        ? adapter.getDeploymentFailure()
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

    normalize();
    validateAndLink();

    // TODO: process workflow-level properties instead of rejecting them once
    //  story 27 implements resolving 'workflows' of WorkflowModuleAdapterProperties.
    //  Fail hard since silently ignoring them could elect the wrong BPMS without
    //  any error.
    final var workflowLevelConfigurations = workflowModules
        .entrySet()
        .stream()
        .filter(workflowModule -> !workflowModule.getValue().getWorkflows().isEmpty())
        .map(workflowModule -> "%s.workflow-modules.%s.workflows".formatted(PREFIX, workflowModule.getKey()))
        .sorted()
        .collect(Collectors.joining("\n  "));
    if (!workflowLevelConfigurations.isEmpty()) {
      throw new IllegalStateException(
          """
              Workflow-level configuration is not yet supported! Remove these properties:
                %s"""
              .formatted(workflowLevelConfigurations));
    }

    if (knownWorkflowModuleIds.isEmpty()) {
      throw new IllegalStateException("No workflow-modules where given!");
    }

    if (adapters.isEmpty()) {
      final var missingConfigSections = adaptersLoaded
          .stream()
          .map(adapter -> "%s.adapters.xxx.type=%s".formatted(PREFIX, adapter))
          .collect(Collectors.joining("\n  "));
      throw new IllegalStateException(
          """
              No adapters configured! Add properties sections for your BPMS (e.g. xxx) having type set to adapters found in classpath:
                %s"""
              .formatted(missingConfigSections));
    }

    final var adaptersNotInClasspath = adapterTypes()
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

    // every workflow module found in the classpath has to be configured
    final var unconfiguredModules = knownWorkflowModuleIds
        .stream()
        .filter(module -> !getWorkflowModules().containsKey(module))
        .collect(Collectors.joining("\n  "));
    if (!unconfiguredModules.isEmpty()) {
      throw new IllegalStateException(
          """
              Unconfigured VanillaBP workflow modules were found in classpath:
                %s
              Add property keys '%s.workflow-modules.*' to configure them."""
              .formatted(unconfiguredModules, PREFIX));
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
              which were not found in the class-path! These properties are never used - remove them
              or add the workflow module (a dependency having a 'META-INF/workflow-module' marker
              file with that ID) to the application.""",
          PREFIX, String.join(propPrefix, workflowModulesConfiguredButNotInClasspath));
    }

    // module-adapter entries which are never used (V1-style check): every key under
    // 'vanillabp.workflow-modules.<module>.adapters.<id>' has to reference a
    // configured adapter id
    final var unusedModuleAdapterEntries = getWorkflowModules()
        .values()
        .stream()
        .flatMap(workflowModule -> workflowModule
            .getAdapters()
            .keySet()
            .stream()
            .filter(adapterId -> !adapters.containsKey(adapterId))
            .map(adapterId -> "%s.workflow-modules.%s.adapters.%s"
                .formatted(PREFIX, workflowModule.workflowModuleId, adapterId)))
        .sorted()
        .collect(Collectors.joining("\n  "));
    if (!unusedModuleAdapterEntries.isEmpty()) {
      throw new IllegalStateException(
          """
              These properties refer to adapter ids not configured in 'vanillabp.adapters.*' - they are never used:
                %s
              Configured adapter ids are: '%s'. Fix the adapter id or add a section 'vanillabp.adapters.<id>'."""
              .formatted(unusedModuleAdapterEntries, String.join("', '", adapters.keySet())));
    }

    // duplicates in prioritized-adapters lists
    validateNoDuplicatePrioritizedAdapters(
        getPrioritizedAdapters(),
        "%s.prioritized-adapters".formatted(PREFIX));

    // if more than one adapter is configured, the property
    // 'vanillabp.prioritized-adapters' has to list each configured adapter
    if (adapters.size() > 1 && (getPrioritizedAdapters().size() != adapters.size())) {
      throw new IllegalStateException(
          """
              The property '%s.prioritized-adapters' must list all the adapters configured in '%s.adapters.*' to define
              the order in which adapters are addressed to find workflows running.
              Configured adapters are: %s."""
              .formatted(PREFIX, PREFIX, String.join(", ", adapters.keySet())));
    }
    getWorkflowModules()
        .values()
        .forEach(workflowModule -> {
          validateNoDuplicatePrioritizedAdapters(
              workflowModule.getPrioritizedAdapters(),
              "%s.workflow-modules.%s.prioritized-adapters".formatted(PREFIX, workflowModule.workflowModuleId));
          workflowModule
              .getWorkflows()
              .values()
              .forEach(workflow -> validateNoDuplicatePrioritizedAdapters(
                  workflow.getPrioritizedAdapters(),
                  "%s.workflow-modules.%s.workflows.%s.prioritized-adapters"
                      .formatted(PREFIX, workflowModule.workflowModuleId, workflow.getBpmnProcessId())));
        });

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
    final var notConfiguredAdapters = new HashMap<String, Set<String>>();
    getPrioritizedAdapters()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .forEach(
            adapterId -> notConfiguredAdapters
                .computeIfAbsent(
                    "%s.prioritized-adapters".formatted(MigrationAdapterProperties.PREFIX),
                    key -> new HashSet<>())
                .add(adapterId));
    getWorkflowModules()
        .values()
        .forEach(
            workflowModule -> {
              workflowModule.getPrioritizedAdapters().stream()
                  .filter(adapterId -> !adapters.containsKey(adapterId))
                  .forEach(
                      adapterId -> notConfiguredAdapters
                          .computeIfAbsent(
                              "%s.workflow-modules.%s.prioritized-adapters".formatted(PREFIX,
                                  workflowModule.workflowModuleId),
                              key -> new HashSet<>())
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
                                  .computeIfAbsent(
                                      "%s.workflow-modules.%s.workflows.%s.prioritized-adapters".formatted(PREFIX,
                                          workflowModule.workflowModuleId, workflow.getBpmnProcessId()),
                                      key -> new HashSet<>())
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

    // resources-location (an empty prioritized-adapters list cannot occur here:
    // a single configured adapter is defaulted by normalize(), multiple adapters
    // are forced into 'vanillabp.prioritized-adapters' by the check above)
    knownWorkflowModuleIds.forEach(
        workflowModuleId -> {
          final var prioritizedAdaptersOfModule = getPrioritizedAdaptersFor(workflowModuleId, null);
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

  /**
   * Validates that environment variables addressing the <code>vanillabp.*</code> tree
   * were actually taken over by the configuration binding. Environment variables can
   * only <b>override</b> entries of dynamic maps (adapters, workflow modules) which
   * are already declared in a configuration file - they cannot <b>introduce</b> a new
   * entry whose ID contains dashes or dots (the binder cannot reconstruct the ID from
   * the variable's name). Such a variable is silently ignored by the binding, so this
   * check fails the startup with a guiding message instead.
   * <p>
   * The check is a best-effort guard on the ID segments: matching is performed on a
   * separator-free uppercase form (<code>c8-cloud</code> matches both
   * <code>C8_CLOUD</code> and <code>C8CLOUD</code>), so unusual IDs sharing a prefix
   * with another configured ID may escape detection - but a valid override is never
   * reported as an error.
   *
   * @param rawPropertyNames The raw names of all properties available, including the
   *          unconverted environment-variable names (both platforms surface them)
   */
  public void validateEnvironmentVariableUsage(
      final Iterable<String> rawPropertyNames) {

    final var envVarPrefix = PREFIX.toUpperCase()
        + "_";
    final var violations = new LinkedList<String>();

    for (final var propertyName : rawPropertyNames) {
      if (!propertyName.matches("^%s[A-Z0-9_]+$".formatted(envVarPrefix))) {
        continue;
      }
      final var path = propertyName.substring(envVarPrefix.length());
      if (path.startsWith("ADAPTERS_")) {
        validateEnvironmentVariableIdSegment(
            propertyName,
            path.substring("ADAPTERS_".length()),
            adapters.keySet(),
            "%s.adapters".formatted(PREFIX),
            violations);
      } else if (path.startsWith("WORKFLOW_MODULES_")) {
        validateEnvironmentVariableIdSegment(
            propertyName,
            path.substring("WORKFLOW_MODULES_".length()),
            workflowModules.keySet(),
            "%s.workflow-modules".formatted(PREFIX),
            violations);
      }
      // all other sections (e.g. prioritized-adapters, resources-location,
      // outbox) carry no dynamic ID segment - a typo there is either caught by
      // the platform's unknown-key detection or harmless to the BPMS election
    }

    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          """
              Environment variables addressing the '%s' configuration were NOT taken over by the configuration binding:
                %s
              Environment variables can only OVERRIDE entries already declared in a configuration file - they
              cannot introduce a new adapter or workflow module (the entry's ID cannot be reconstructed from the
              variable's name). Declare the ID in a configuration file (e.g. 'vanillabp.adapters.<id>.type') and
              use the environment variable to override its values, or fix the ID part of the variable's name."""
              .formatted(PREFIX, String.join("\n  ", violations)));
    }

  }

  private static void validateEnvironmentVariableIdSegment(
      final String propertyName,
      final String idAndRemainder,
      final Set<String> configuredIds,
      final String sectionKey,
      final List<String> violations) {

    final var comparableRemainder = idAndRemainder.replace("_", "");
    final var matches = configuredIds
        .stream()
        .map(id -> id.toUpperCase().replaceAll("[^A-Z0-9]", ""))
        .anyMatch(comparableRemainder::startsWith);
    if (!matches) {
      violations.add(
          "'%s' does not address any of the IDs configured in '%s.*' (%s)"
              .formatted(
                  propertyName,
                  sectionKey,
                  configuredIds.isEmpty()
                      ? "none configured"
                      : "'%s'".formatted(String.join("', '", configuredIds))));
    }

  }

  private static void validateNoDuplicatePrioritizedAdapters(
      final List<String> prioritizedAdapters,
      final String propertyKey) {

    final var duplicates = prioritizedAdapters
        .stream()
        .filter(adapterId -> prioritizedAdapters
            .stream()
            .filter(adapterId::equals)
            .count() > 1)
        .distinct()
        .collect(Collectors.joining("', '"));
    if (!duplicates.isEmpty()) {
      throw new IllegalStateException(
          """
              The property '%s' lists these adapter ids more than once: '%s'!
              Remove the duplicates - the order of the remaining entries defines the priority."""
              .formatted(propertyKey, duplicates));
    }

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
