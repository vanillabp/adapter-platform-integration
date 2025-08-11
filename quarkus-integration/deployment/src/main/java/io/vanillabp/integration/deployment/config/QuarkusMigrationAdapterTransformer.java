package io.vanillabp.integration.deployment.config;

import static io.vanillabp.integration.deployment.VanillaBpBuildStepProcessor.PREFIX_ADAPTER_PACKAGE;
import static io.vanillabp.integration.deployment.config.QuarkusMigrationAdapterProperties.PREFIX;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.quarkus.deployment.Capabilities;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import lombok.Builder;

/**
 * Turns {@link QuarkusMigrationAdapterTransformer} into
 * {@link MigrationAdapterProperties}. Validates values of properties
 * which are specific to Quarkus. Further validation is done by
 * {@link MigrationAdapterProperties#validateProperties(List, List)}
 * or {@link MigrationAdapterProperties#validatePropertiesFor(List, String, String)}.
 */
@Builder
public class QuarkusMigrationAdapterTransformer {

  private final QuarkusMigrationAdapterProperties properties;

  private final Capabilities capabilities;

  /**
   * Transforms {@link QuarkusMigrationAdapterTransformer} into
   * {@link MigrationAdapterProperties}.
   *
   * @param processServiceBuildItems Build items provided by other VanillaBP adapter extensions
   * @return The {@link MigrationAdapterProperties}
   * @throws IllegalStateException If validation fails
   */
  public MigrationAdapterProperties getAndValidatePropertiesConfigured(
      final List<VanillaBpMigratableProcessServiceBuildItem> processServiceBuildItems) throws IllegalStateException {

    final var result = new MigrationAdapterProperties();

    final var adaptersConfigured = getAndValidateAdaptersConfigured();
    result.setAdapters(adaptersConfigured);

    final var prioritizedAdaptersConfigured = getAndValidatePrioritizedAdaptersConfigured(
        adaptersConfigured);
    result.setPrioritizedAdapters(prioritizedAdaptersConfigured);

    final var workflowModulesConfigured = getAndValidateWorkflowModulesConfigured();
    result.setWorkflowModules(workflowModulesConfigured);

    final var buildItemsNotConfigured = processServiceBuildItems
        .stream()
        .map(VanillaBpMigratableProcessServiceBuildItem::getAdapterName)
        .filter(Predicate.not(adaptersConfigured::containsValue))
        .collect(Collectors.joining("\n  "));
    if (!buildItemsNotConfigured.isEmpty()) {
      throw new IllegalStateException(
          """
              Found VanillaBpMigratableProcessServiceBuildItem mapping to adapters not found in properties '%s.adapters.*.type':
                %s
              This happens only in case the adapter extensions VanillaBpMigratableProcessServiceBuildItem
              adapter name does not match the suffix of its capability prefixed by '%s'!"""
              .formatted(PREFIX, buildItemsNotConfigured, PREFIX_ADAPTER_PACKAGE));
    }

    final var buildItemsAdapters = processServiceBuildItems
        .stream()
        .map(VanillaBpMigratableProcessServiceBuildItem::getAdapterName)
        .toList();
    final var missingBuildItems = adaptersConfigured
        .values()
        .stream()
        .filter(type -> !buildItemsAdapters.contains(type))
        .collect(Collectors.joining("\n  "));
    if (!missingBuildItems.isEmpty()) {
      throw new IllegalStateException(
          """
              Illegal VanillaBP adapter extensions:
                %s
              Missing VanillaBpMigratableProcessServiceBuildItem!"""
              .formatted(missingBuildItems));
    }
    final var buildItemsWithoutCapability = buildItemsAdapters
        .stream()
        .filter(adapter -> !adaptersConfigured.containsValue(adapter))
        .collect(Collectors.joining("\n  "));
    if (!buildItemsWithoutCapability.isEmpty()) {
      throw new IllegalStateException(
          """
              Illegal VanillaBP adapter extensions:
                '%s'
              Not matching their extension capabilities!"""
              .formatted(missingBuildItems));
    }

    return result;

  }

  private Map<String, WorkflowModuleAdapterProperties> getAndValidateWorkflowModulesConfigured() {

    return Map.of();

  }

  private Map<String, String> getAndValidateAdaptersConfigured() {

    // determine adapters by examining capabilities of Quarkus extensions available:
    final var adapterPackagesProvidedByOtherExtensions = capabilities
        .getCapabilities()
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
