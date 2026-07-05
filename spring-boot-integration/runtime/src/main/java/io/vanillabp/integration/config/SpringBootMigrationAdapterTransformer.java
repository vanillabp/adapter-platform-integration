package io.vanillabp.integration.config;

import static io.vanillabp.integration.config.SpringBootMigrationAdapterProperties.PREFIX;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import lombok.Builder;

/**
 * Turns {@link SpringBootMigrationAdapterProperties} into
 * {@link MigrationAdapterProperties}. Validates values of properties
 * which are specific to Quarkus. Further validation is done by
 * {@link MigrationAdapterProperties#validateProperties(List, List)}
 * or {@link MigrationAdapterProperties#validatePropertiesFor(List, String, String)}.
 */
@Builder(toBuilder = true)
public class SpringBootMigrationAdapterTransformer {

  /**
   * The properties to transform
   */
  private SpringBootMigrationAdapterProperties properties;

  /**
   * Adapters found in classpath
   */
  private List<String> adaptersFound;

  /**
   * Workflow modules found in the classpath
   */
  private List<String> workflowModulesFound;

  /**
   * Transforms {@link SpringBootMigrationAdapterProperties} into
   * {@link MigrationAdapterProperties}.
   *
   * @return The {@link MigrationAdapterProperties}
   */
  public MigrationAdapterProperties getAndValidatePropertiesConfigured() {

    // TODO: process workflow-level properties instead of rejecting them once
    //  filling 'workflows' of WorkflowModuleAdapterProperties is implemented
    rejectWorkflowLevelConfiguration();

    final var result = new MigrationAdapterProperties();

    result.setResourcesLocation(properties.getResourcesLocation());

    // validate properties of adapters against adapters found in classpath
    final var adaptersConfigured = getAndValidateAdaptersConfigured();
    result.setAdapters(adaptersConfigured);

    // validate priorities of adapters configured against adapters found in classpath
    final var prioritizedAdaptersConfigured = getAndValidatePrioritizedAdaptersConfigured(
        adaptersConfigured);
    result.setPrioritizedAdapters(prioritizedAdaptersConfigured);

    // validate properties of workflow modules against workflow modules found in classpath
    final var workflowModulesConfigured = getAndValidateWorkflowModulesConfigured();
    result.setWorkflowModules(workflowModulesConfigured);

    result.validateProperties(adaptersFound, workflowModulesFound);

    return result;

  }

  /**
   * Fail hard on workflow-level properties (<code>vanillabp.workflow-modules.*.workflows.*</code>)
   * since they are not yet supported. Silently ignoring them could elect the wrong BPMS
   * without any error.
   *
   * @throws IllegalStateException If workflow-level properties are configured
   */
  private void rejectWorkflowLevelConfiguration() {

    final var workflowLevelConfigurations = properties
        .getWorkflowModules()
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

  }

  /**
   * Determine workflow module properties and validate them against workflow modules found in classpath.
   *
   * @return Map of workflow modules (key = workflow module ID, value = properties)
   */
  private Map<String, WorkflowModuleAdapterProperties> getAndValidateWorkflowModulesConfigured() {

    final var result = properties
        .getWorkflowModules()
        .entrySet()
        .stream()
        .map(workflowModule -> Map.entry(
            workflowModule.getKey(),
            (WorkflowModuleAdapterProperties) WorkflowModuleAdapterProperties
                .builder()
                .workflowModuleId(workflowModule.getKey())
                .prioritizedAdapters(workflowModule.getValue().getPrioritizedAdapters())
                // TODO fill workflows; until implemented, configured workflow-level
                //  properties are rejected by rejectWorkflowLevelConfiguration()
                .workflows(Map.of())
                .adapters(workflowModule
                    .getValue()
                    .getAdapters()
                    .entrySet()
                    .stream()
                    .map(adapter -> Map.entry(
                        adapter.getKey(),
                        AdapterProperties
                            .builder()
                            .resourcesLocation(adapter.getValue().getResourcesLocation())
                            .build()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .build()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    if (result.isEmpty() && !workflowModulesFound.isEmpty()) {
      final var missingConfigSections = workflowModulesFound
          .stream()
          .map(module -> "%s.workflow-modules.%s".formatted(PREFIX, module))
          .collect(Collectors.joining("', '"));
      throw new IllegalStateException(
          "No workflow-modules configured! Add properties sections '%s'.".formatted(missingConfigSections));
    }

    // check for unconfigured workflow modules
    final var unconfiguredModules = workflowModulesFound
        .stream()
        .filter(module -> !result.containsKey(module))
        .collect(Collectors.joining("\n, "));
    if (!unconfiguredModules.isEmpty()) {
      throw new IllegalStateException(
          """
              Unconfigured VanillaBP workflow modules were found in classpath:
                %s
              Add property keys '%s.workflow-modules.*' to configure them."""
              .formatted(unconfiguredModules, PREFIX));
    }

    // check for unknown adapters
    final var unknownModules = result
        .keySet()
        .stream()
        .filter(workflowModuleAdapterProperties -> !workflowModulesFound.contains(workflowModuleAdapterProperties))
        .map(workflowModuleAdapterProperties -> "%s.workflow-modules.%s".formatted(PREFIX,
            workflowModuleAdapterProperties))
        .collect(Collectors.joining("\n, "));
    if (!unknownModules.isEmpty()) {
      throw new IllegalStateException(
          """
              Property keys '%s.workflow-modules.*' must name VanillaBP workflow modules available in classpath!
              These unknown workflow modules were found in properties:
                %s
              Available workflow modules currently loaded in classpath: '%s'."""
              .formatted(PREFIX, unknownModules, String.join("', '", workflowModulesFound)));
    }

    return result;

  }

  /**
   * Determine adapters configured based adapters found in classpath and properties configured.
   *
   * @return Map of adapters (key = adapter name, value = adapter type)
   */
  private Map<String, String> getAndValidateAdaptersConfigured() {

    if (adaptersFound.isEmpty()) {
      throw new IllegalStateException(
          "No adapters found in classpath! Add dependencies providing VanillaBP adapters.");
    }

    // build result map (key = adapter name, value = adapter type)
    final var result = properties
        .getAdapters()
        .entrySet()
        .stream()
        .map(config -> Map.entry(config.getKey(),
            Optional.ofNullable(config.getValue().getType()).orElse(config.getKey())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    if (result.isEmpty()) {
      final var missingConfigSections = adaptersFound
          .stream()
          .map(adapter -> "%s.adapters.xxx.type=%s".formatted(PREFIX, adapter))
          .collect(Collectors.joining("\n"));
      throw new IllegalStateException(
          """
              No adapters configured! Add properties sections for your BPMS (e.g. xxx) having type set to adapters found in classpath:
                %s"""
              .formatted(missingConfigSections));
    }

    // check for unknown adapters
    final var unknownAdapters = result
        .entrySet()
        .stream()
        .filter(adapter -> !adaptersFound.contains(adapter.getValue()))
        .map(adapter -> "'%s' found in '%s.adapters.%s.type'".formatted(adapter.getValue(), PREFIX, adapter.getKey()))
        .collect(Collectors.joining(", "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              Properties '%s.adapters.*.type' must contain VanillaBP adapters available in classpath!
              These adapters are unknown: %s.
              Available adapter types currently loaded in classpath: '%s'."""
              .formatted(PREFIX, unknownAdapters, String.join("', '", adaptersFound)));
    }

    return result;

  }

  /**
   * Determine priorities of adapters configured.
   *
   * @param adapters The adapters found
   * @return List of adapter ordered by configured priorities
   */
  private List<String> getAndValidatePrioritizedAdaptersConfigured(
      final Map<String, String> adapters) {

    // It is OK to skip property vanillabp.prioritized-adapters in case
    // only one adapter is configured:
    if (properties.getPrioritizedAdapters().isEmpty() && (adapters.size() == 1)) {
      return adapters.keySet().stream().toList();
    }

    // if more than one adapter is configured then the
    // property vanillabp.prioritized-adapters has to list each adapter
    // configured:
    final var adapterIdsConfigured = String.join(", ", adapters.keySet());
    if (properties.getPrioritizedAdapters()
        .isEmpty() || (adapters.size() != properties.getPrioritizedAdapters().size())) {
      throw new IllegalStateException(
          """
              The property '%s.prioritized-adapters' must list all the adapters configured in '%s.adapters.*' to define
              the order in which adapters are addressed to find workflows running.
              Configured adapters are: %s."""
              .formatted(PREFIX, PREFIX, adapterIdsConfigured));
    }

    final var unknownAdapters = properties
        .getPrioritizedAdapters()
        .stream()
        .filter(adapter -> !adapters.containsKey(adapter))
        .map(adapter -> "%s -> '%s.adapters.%s'".formatted(adapter, PREFIX, adapter))
        .collect(Collectors.joining("\n  "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              The property '%s.prioritized-adapters' lists these adapters for which no property sections were found:
                %s"""
              .formatted(PREFIX, unknownAdapters));
    }

    return properties.getPrioritizedAdapters();

  }

}
