package io.vanillabp.integration.config;

import static io.vanillabp.integration.config.SpringBootMigrationAdapterProperties.PREFIX;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Builder;

@Builder
public class SpringBootMigrationAdapterTransformer {

  private SpringBootMigrationAdapterProperties properties;

  private List<String> adaptersLoaded;

  public MigrationAdapterProperties getAndValidatePropertiesConfigured() {

    final var result = new MigrationAdapterProperties();

    final var adaptersConfigured = getAndValidateAdaptersConfigured();
    result.setAdapters(adaptersConfigured);

    final var prioritizedAdaptersConfigured = getAndValidatePrioritizedAdaptersConfigured(
        adaptersConfigured);
    result.setPrioritizedAdapters(prioritizedAdaptersConfigured);

    final var workflowModulesConfigured = getAndValidateWorkflowModulesConfigured();
    result.setWorkflowModules(workflowModulesConfigured);

    // TODO add knownWorkflowModules
    result.validateProperties(adaptersLoaded, List.of());

    return result;

  }

  private Map<String, WorkflowModuleAdapterProperties> getAndValidateWorkflowModulesConfigured() {

    return Map.of();

  }

  private Map<String, String> getAndValidateAdaptersConfigured() {

    if (adaptersLoaded.isEmpty()) {
      throw new IllegalStateException(
          "No adapters found! Add dependencies providing VanillaBP adapters.");
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
      final var missingConfigSections = adaptersLoaded
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
        .filter(adapter -> !adaptersLoaded.contains(adapter.getValue()))
        .map(adapter -> "%s found in %s.adapters.%s".formatted(adapter.getValue(), PREFIX, adapter.getKey()))
        .collect(Collectors.joining(", "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          "Properties '%s.adapters.*.type' must contain VanillaBP adapters "
              + "added as Quarkus extension!\nThese adapters are unknown: %s.\n"
              + "Available adapter types provided by Quarkus extensions currently loaded: %s."
                  .formatted(PREFIX, unknownAdapters, String.join(", ", adaptersLoaded)));
    }

    return result;

  }

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
    final var adapterNamesConfigured = String.join(", ", adapters.keySet());
    if (properties.getPrioritizedAdapters()
        .isEmpty() || (adapters.size() != properties.getPrioritizedAdapters().size())) {
      throw new IllegalStateException(
          "The property '%s.prioritized-adapters' must list all the adapters "
              + "configured in '%s.adapters.*' to define the order in which "
              + "adapters are addressed to find workflows running.\n"
              + "These are: %s."
                  .formatted(PREFIX, PREFIX, adapterNamesConfigured));
    }

    final var unknownAdapters = properties
        .getPrioritizedAdapters()
        .stream()
        .filter(adapter -> !adapterNamesConfigured.contains(adapter))
        .collect(Collectors.joining(", "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          "The property '%s.prioritized-adapters' lists these adapters for which "
              + "no properties '%s.adapters.*' were found: %s!"
                  .formatted(PREFIX, PREFIX, unknownAdapters));
    }

    return properties.getPrioritizedAdapters();

  }

}
