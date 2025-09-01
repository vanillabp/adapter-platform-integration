package io.vanillabp.integration.runtime.config;

import static io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties.PREFIX;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.AdapterConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import lombok.Builder;

/**
 * Turns {@link QuarkusMigrationAdapterProperties} into
 * {@link MigrationAdapterProperties}. Validates values of properties
 * which are specific to Quarkus. Further validation is done by
 * {@link MigrationAdapterProperties#validateProperties(List, List)}
 * or {@link MigrationAdapterProperties#validatePropertiesFor(List, String, String)}.
 */
@Builder
public class QuarkusMigrationAdapterTransformer {

  /**
   * Each VanillaBP adapter is a Quarkus extension publishing a Quarkus extension
   * capability with its name prefixed by this prefix. e.g. io.vanillabp.adapter.dummy
   */
  public static final String PREFIX_ADAPTER_PACKAGE = "io.vanillabp.adapter.";

  /**
   * The properties to transform
   */
  private final QuarkusMigrationAdapterProperties properties;

  /**
   * Capabilities of Quarkus extensions available
   */
  private final Collection<String> capabilities;

  /**
   * Transforms {@link QuarkusMigrationAdapterProperties} into
   * {@link MigrationAdapterProperties}.
   *
   * @param workflowModulesFound All workflow modules found during augmentation.
   * @param adaptersFound All adapters found during augmentation.
   * @return The {@link MigrationAdapterProperties}
   * @throws IllegalStateException If validation fails
   */
  public MigrationAdapterProperties getAndValidatePropertiesConfigured(
      final Collection<WorkflowModule> workflowModulesFound,
      final Collection<String> adaptersFound) throws IllegalStateException {

    final var result = new MigrationAdapterProperties();

    // validate properties of adapters against adapters found in classpath
    final var adaptersConfigured = getAndValidateAdaptersConfigured(
        adaptersFound);
    result.setAdapters(adaptersConfigured);

    // validate priorities of adapters configured against adapters found in classpath
    final var prioritizedAdaptersConfigured = getAndValidatePrioritizedAdaptersConfigured(
        adaptersConfigured);
    result.setPrioritizedAdapters(prioritizedAdaptersConfigured);

    // validate properties of workflow modules against workflow modules found in classpath
    final var workflowModulesConfigured = getAndValidateWorkflowModulesConfigured(
        workflowModulesFound);
    result.setWorkflowModules(workflowModulesConfigured);

    return result;

  }

  /**
   * Determine workflow module properties and validate them against workflow modules found in classpath.
   *
   * @param workflowModulesFound All workflow modules found based on META-INF/workflow-module files
   * @return Map of workflow modules (key = workflow module ID, value = properties)
   */
  private Map<String, WorkflowModuleAdapterProperties> getAndValidateWorkflowModulesConfigured(
      final Collection<WorkflowModule> workflowModulesFound) {

    final var knownWorkflowModuleIds = workflowModulesFound
        .stream()
        .map(WorkflowModule::getId)
        .toList();

    final var result = properties
        .workflowModules()
        .entrySet()
        .stream()
        .map(config -> Map.entry(
            config.getKey(),
            (WorkflowModuleAdapterProperties) WorkflowModuleAdapterProperties
                .builder()
                .workflowModuleId(config.getKey())
                .workflows(Map.of()) // TODO fill workflows
                .adapters(config
                    .getValue()
                    .adapters()
                    .entrySet()
                    .stream()
                    .map(adapter -> Map.entry(
                        adapter.getKey(),
                        AdapterConfiguration
                            .builder()
                            .resourcesLocation(adapter.getValue().resourcesLocation())
                            .build()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .build()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    if (result.isEmpty() && !knownWorkflowModuleIds.isEmpty()) {
      final var missingConfigSections = knownWorkflowModuleIds
          .stream()
          .map(module -> "%s.workflow-modules.%s".formatted(PREFIX, module))
          .collect(Collectors.joining("', '"));
      throw new IllegalStateException(
          "No workflow-modules configured! Add properties sections '%s'.".formatted(missingConfigSections));
    }

    // check for unconfigured workflow modules
    final var unconfiguredModules = knownWorkflowModuleIds
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
        .filter(workflowModuleAdapterProperties -> !knownWorkflowModuleIds.contains(workflowModuleAdapterProperties))
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
              .formatted(PREFIX, unknownModules, String.join("', '", knownWorkflowModuleIds)));
    }

    return result;

  }

  /**
   * Determine adapters configured based on capabilities of VanillaBP adapter
   * Quarkus extensions and properties configured.
   *
   * @param adaptersFound All adapters found during augmentation.
   * @return Map of adapters (key = adapter name, value = adapter type)
   */
  private Map<String, String> getAndValidateAdaptersConfigured(
      final Collection<String> adaptersFound) {

    // determine adapters by examining capabilities of Quarkus extensions available:
    final var adapterPackagesProvidedByOtherExtensions = capabilities
        .stream()
        .filter(capability -> capability.startsWith(PREFIX_ADAPTER_PACKAGE))
        .toList();
    final var adapterNamesProvidedByOtherExtensions = adapterPackagesProvidedByOtherExtensions
        .stream()
        .map(pkg -> pkg.substring(PREFIX_ADAPTER_PACKAGE.length()))
        .toList();
    if (adapterPackagesProvidedByOtherExtensions.isEmpty()) {
      throw new IllegalStateException(
          "No extensions found with capabilities '%s*'! Add Quarkus extensions providing VanillaBP adapters."
              .formatted(PREFIX_ADAPTER_PACKAGE));
    }

    // build result map (key = adapter name, value = adapter type)
    final var result = properties
        .adapters()
        .entrySet()
        .stream()
        // map type property, if not set, to the adapters name
        .map(config -> Map.entry(config.getKey(), config.getValue().type().orElse(config.getKey())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    if (result.isEmpty()) {
      final var missingConfigSections = adapterNamesProvidedByOtherExtensions
          .stream()
          .map(adapter -> "%s.adapters.xxxx.type=%s".formatted(PREFIX, adapter))
          .collect(Collectors.joining("\n  "));
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
        .filter(adapter -> !adapterNamesProvidedByOtherExtensions.contains(adapter.getValue()))
        .map(adapter -> "'%s' found in '%s.adapters.%s.type'".formatted(adapter.getValue(), PREFIX, adapter.getKey()))
        .collect(Collectors.joining("\n  "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              Properties '%s.adapters.*.type' must contain VanillaBP adapters added as Quarkus extension!
              These adapters are unknown:
                %s
              Available adapter types provided by Quarkus extensions currently loaded: %s."""
              .formatted(PREFIX, unknownAdapters,
                  String.join(", ", adapterNamesProvidedByOtherExtensions)));
    }

    // validate adapters provided by VanillaBP Quarkus adapter extensions
    final var extensionsWithoutCapability = adaptersFound
        .stream()
        .filter(adapter -> !adapterNamesProvidedByOtherExtensions.contains(adapter))
        .collect(Collectors.joining("\n  "));
    if (!extensionsWithoutCapability.isEmpty()) {
      throw new IllegalStateException(
          """
              Illegal VanillaBP adapter extensions:
                '%s'
              are not matching their extension capabilities!"""
              .formatted(extensionsWithoutCapability));
    }

    // validate properties against adapters provided by VanillaBP Quarkus adapter extensions
    final var missingAdapters = result
        .values()
        .stream()
        .filter(type -> !adaptersFound.contains(type))
        .collect(Collectors.joining("\n  "));
    if (!missingAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              Missing VanillaBP adapter extensions for these types found in properties:
                %s"""
              .formatted(missingAdapters));
    }

    // validate properties of process services against adapters provided by VanillaBP Quarkus adapter extensions
    final var buildItemsNotConfigured = adaptersFound
        .stream()
        .filter(Predicate.not(result::containsValue))
        .map(adapter -> "%s.adapters.*.type=%s".formatted(PREFIX, adapter))
        .collect(Collectors.joining("\n  "));
    if (!buildItemsNotConfigured.isEmpty()) {
      throw new IllegalStateException(
          """
              VanillaBP Quarkus adapter extensions found but not configured.
              Add adapter specific configuration in properties sections having type set:
                %s"""
              .formatted(buildItemsNotConfigured));
    }

    // test for adapters not found in properties
    final var unconfiguredAdapters = adapterNamesProvidedByOtherExtensions
        .stream()
        .filter(adapter -> !result.containsValue(adapter))
        .collect(Collectors.joining(", "));
    if (!unconfiguredAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              No '%s.adapters.*' properties sections having types provided by Quarkus extension!
              Add section section if intended or remove extensions for these types: %s."""
              .formatted(PREFIX, unconfiguredAdapters));
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
    if (properties.prioritizedAdapters().isEmpty() && (adapters.size() == 1)) {
      return adapters.keySet().stream().toList();
    }

    // if more than one adapter is configured then the
    // property vanillabp.prioritized-adapters has to list each adapter
    // configured:
    if (properties.prioritizedAdapters()
        .isEmpty() || (adapters.size() != properties.prioritizedAdapters().get().size())) {
      throw new IllegalStateException(
          """
              The property '%s.prioritized-adapters' must list all the adapters configured in '%s.adapters.*' to define
              the order in which adapters are addressed to find workflows running.
              Configured adapters are: %s."""
              .formatted(PREFIX, PREFIX, String.join(", ", adapters.keySet())));
    }

    final var unknownAdapters = properties
        .prioritizedAdapters()
        .get()
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

    return properties.prioritizedAdapters().get();

  }

}
