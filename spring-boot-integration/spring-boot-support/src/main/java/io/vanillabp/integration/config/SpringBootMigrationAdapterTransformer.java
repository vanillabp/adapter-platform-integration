package io.vanillabp.integration.config;

import static io.vanillabp.integration.config.SpringBootMigrationAdapterProperties.PREFIX;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.vanillabp.integration.modules.WorkflowModuleProperties;
import lombok.Builder;

@Builder
public class SpringBootMigrationAdapterTransformer {

  private SpringBootMigrationAdapterProperties properties;

  private List<String> adaptersLoaded;

  private List<String> workflowModulesLoaded;

  public MigrationAdapterProperties getAndValidatePropertiesConfigured() {

    final var result = new MigrationAdapterProperties();

    final var adaptersConfigured = getAndValidateAdaptersConfigured();
    result.setAdapters(adaptersConfigured);

    final var prioritizedAdaptersConfigured = getAndValidatePrioritizedAdaptersConfigured(
        adaptersConfigured);
    result.setPrioritizedAdapters(prioritizedAdaptersConfigured);

    final var workflowModulesConfigured = getAndValidateWorkflowModulesConfigured();
    result.setWorkflowModules(workflowModulesConfigured);

    result.validateProperties(adaptersLoaded, workflowModulesLoaded);

    return result;

  }

  private Map<String, WorkflowModuleAdapterProperties> getAndValidateWorkflowModulesConfigured() {

    if (workflowModulesLoaded.isEmpty()) {
      throw new IllegalStateException(
          "No workflow-modules found! Add dependencies providing static beans of type '%s'."
              .formatted(WorkflowModuleProperties.class));
    }

    final var result = properties
        .getWorkflowModules()
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
                    .getAdapters()
                    .entrySet()
                    .stream()
                    .map(adapter -> Map.entry(
                        adapter.getKey(),
                        AdapterConfiguration
                            .builder()
                            .resourcesLocation(adapter.getValue().getResourcesLocation())
                            .build()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .build()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    if (result.isEmpty()) {
      final var missingConfigSections = workflowModulesLoaded
          .stream()
          .map(module -> "%s.workflow-modules.%s".formatted(PREFIX, module))
          .collect(Collectors.joining(", "));
      throw new IllegalStateException(
          "No workflow-modules configured! Add config sections %s".formatted(missingConfigSections));
    }

    // check for unknown adapters
    final var unknownModules = result
        .keySet()
        .stream()
        .filter(workflowModuleAdapterProperties -> !workflowModulesLoaded.contains(workflowModuleAdapterProperties))
        .map(workflowModuleAdapterProperties -> "%s.workflow-module.%s".formatted(PREFIX,
            workflowModuleAdapterProperties))
        .collect(Collectors.joining("\n, "));
    if (!unknownModules.isEmpty()) {
      throw new IllegalStateException(
          """
              Properties '%s.workflow-modules.*' must contain VanillaBP workflow modules available in classpath!
              These workflow modules are unknown:
                %s
              Available workflow modules currently loaded in classpath: %s."""
              .formatted(PREFIX, unknownModules, String.join(", ", workflowModulesLoaded)));
    }

    return result;

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
          """
              Properties '%s.adapters.*.type' must contain VanillaBP adapters available in classpath!
              These adapters are unknown: %s.
              Available adapter types currently loaded in classpath: %s."""
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
          """
              The property '%s.prioritized-adapters' must list all the adapters configured in '%s.adapters.*' to define the order in which adapters are addressed to find workflows running.
              These are: %s."""
              .formatted(PREFIX, PREFIX, adapterNamesConfigured));
    }

    final var unknownAdapters = properties
        .getPrioritizedAdapters()
        .stream()
        .filter(adapter -> !adapterNamesConfigured.contains(adapter))
        .collect(Collectors.joining(", "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException(
          "The property '%s.prioritized-adapters' lists these adapters for which no properties '%s.adapters.*' were found: %s!"
              .formatted(PREFIX, PREFIX, unknownAdapters));
    }

    return properties.getPrioritizedAdapters();

  }

}
