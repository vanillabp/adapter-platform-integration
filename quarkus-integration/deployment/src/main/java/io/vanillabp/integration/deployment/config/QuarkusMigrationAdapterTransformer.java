package io.vanillabp.integration.deployment.config;

import static io.vanillabp.integration.deployment.config.QuarkusMigrationAdapterProperties.PREFIX;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.quarkus.deployment.Capabilities;
import io.vanillabp.integration.config.MigrationAdapterProperties;
import io.vanillabp.integration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.deployment.VanillaBpIntegrationProcessor;
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
   * @return The {@link MigrationAdapterProperties}
   * @throws IllegalStateException If validation fails
   */
  public MigrationAdapterProperties getAndValidatePropertiesConfigured() throws IllegalStateException {

    final var result = new MigrationAdapterProperties();

    final var adaptersConfigured = getAndValidateAdaptersConfigured();
    result.setAdapters(adaptersConfigured);

    final var prioritizedAdaptersConfigured = getAndValidatePrioritizedAdaptersConfigured(
        adaptersConfigured);
    result.setPrioritizedAdapters(prioritizedAdaptersConfigured);

    final var workflowModulesConfigured = getAndValidateWorkflowModulesConfigured();
    result.setWorkflowModules(workflowModulesConfigured);

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
        .filter(capability -> capability.startsWith(
            VanillaBpIntegrationProcessor.PREFIX_ADAPTER_PACKAGE))
        .toList();
    final var adapterNamesProvidedByOtherExtensions = adapterPackagesProvidedByOtherExtensions
        .stream()
        .map(pkg -> pkg.substring(
            VanillaBpIntegrationProcessor.PREFIX_ADAPTER_PACKAGE.length()))
        .toList();
    if (adapterPackagesProvidedByOtherExtensions.isEmpty()) {
      throw new IllegalStateException(
          "No adapters found! Add Quarkus extensions providing VanillaBP adapters.");
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
          .map(adapter -> "%s.adapters.%s".formatted(PREFIX, adapter))
          .collect(Collectors.joining(", "));
      throw new IllegalStateException(
          "No adapters configured! Add config sections %s".formatted(missingConfigSections));
    }

    // check for unknown adapters
    final var unknownAdapters = result
        .entrySet()
        .stream()
        .filter(adapter -> !adapterNamesProvidedByOtherExtensions.contains(adapter.getValue()))
        .map(adapter -> "%s found in %s.adapters.%s".formatted(adapter.getValue(), PREFIX, adapter.getKey()))
        .collect(Collectors.joining(", "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          "Properties '%s.adapters.*.type' must contain VanillaBP adapters "
              + "added as Quarkus extension!\nThese adapters are unknown: %s.\n"
              + "Available adapter types provided by Quarkus extensions currently loaded: %s."
                  .formatted(PREFIX, unknownAdapters,
                      String.join(", ", adapterNamesProvidedByOtherExtensions)));
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
    final var adapterNamesConfigured = String.join(", ", adapters.keySet());
    if (properties.prioritizedAdapters()
        .isEmpty() || (adapters.size() != properties.prioritizedAdapters().get().size())) {
      throw new IllegalStateException(
          "The property '%s.prioritized-adapters' must list all the adapters "
              + "configured in '%s.adapters.*' to define the order in which "
              + "adapters are addressed to find workflows running.\n"
              + "These are: %s."
                  .formatted(PREFIX, PREFIX, adapterNamesConfigured));
    }

    final var unknownAdapters = properties
        .prioritizedAdapters()
        .get()
        .stream()
        .filter(adapter -> !adapterNamesConfigured.contains(adapter))
        .collect(Collectors.joining(", "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          "The property '%s.prioritized-adapters' lists these adapters for which "
              + "no properties '%s.adapters.*' were found: %s!"
                  .formatted(PREFIX, PREFIX, unknownAdapters));
    }

    return properties.prioritizedAdapters().get();

  }

}
