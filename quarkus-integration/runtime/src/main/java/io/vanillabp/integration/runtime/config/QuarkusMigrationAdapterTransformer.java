package io.vanillabp.integration.runtime.config;

import static io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties.PREFIX;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
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
   * Matches raw configuration keys of workflow-level properties
   * (e.g. <code>vanillabp.workflow-modules.my-module.workflows.MyProcess.prioritized-adapters</code>).
   */
  private static final Pattern WORKFLOW_LEVEL_PROPERTY = Pattern.compile(
      "^%s\\.workflow-modules\\.(\"[^\"]+\"|[^.]+)\\.workflows\\..+".formatted(Pattern.quote(PREFIX)));

  /**
   * The properties to transform
   */
  private final QuarkusMigrationAdapterProperties properties;

  /**
   * Raw names of all properties available. Used to detect workflow-level properties which are
   * not modeled by {@link QuarkusMigrationAdapterProperties} (yet) and hence would be ignored silently.
   */
  private final Iterable<String> propertyNames;

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

    // TODO: process workflow-level properties instead of rejecting them once
    //  filling 'workflows' of WorkflowModuleAdapterProperties is implemented
    rejectWorkflowLevelConfiguration();

    final var result = new MigrationAdapterProperties();

    result.setResourcesLocation(properties.resourcesLocation().orElse(null));

    // validate properties of adapters against adapters found in the classpath
    final var adaptersConfigured = getAndValidateAdaptersConfigured(
        adaptersFound);
    result.setAdapters(adaptersConfigured);

    // validate per-adapter deployment-failure policies
    final var deploymentFailuresConfigured = getAndValidateDeploymentFailuresConfigured();
    result.setDeploymentFailures(deploymentFailuresConfigured);

    // validate priorities of adapters configured against adapters found in the classpath
    final var prioritizedAdaptersConfigured = getAndValidatePrioritizedAdaptersConfigured(
        adaptersConfigured);
    result.setPrioritizedAdapters(prioritizedAdaptersConfigured);

    // map properties of workflow modules
    final var workflowModulesConfigured = getWorkflowModulesConfigured();
    result.setWorkflowModules(workflowModulesConfigured);

    // run the core validation - one validation, in core, identical on all
    // platforms (adapter types are the capability suffixes of the VanillaBP
    // adapter extensions loaded)
    final var adapterTypesProvidedByExtensions = capabilities
        .stream()
        .filter(capability -> capability.startsWith(PREFIX_ADAPTER_PACKAGE))
        .map(pkg -> pkg.substring(PREFIX_ADAPTER_PACKAGE.length()))
        .toList();
    final var knownWorkflowModuleIds = workflowModulesFound
        .stream()
        .map(WorkflowModule::getId)
        .toList();
    result.validateProperties(adapterTypesProvidedByExtensions, knownWorkflowModuleIds);

    // Quarkus-specific consistency: every adapter extension has to be configured
    validateAllExtensionsConfigured(adaptersFound, adapterTypesProvidedByExtensions, adaptersConfigured);

    return result;

  }

  /**
   * Fail hard on workflow-level properties (<code>vanillabp.workflow-modules.*.workflows.*</code>)
   * since they are not yet supported. Silently ignoring them could elect the wrong BPMS
   * without any error. Since {@link QuarkusMigrationAdapterProperties} does not model
   * workflow-level properties, the raw configuration keys are examined.
   *
   * @throws IllegalStateException If workflow-level properties are configured
   */
  private void rejectWorkflowLevelConfiguration() {

    if (propertyNames == null) {
      return;
    }
    final var workflowLevelConfigurations = StreamSupport
        .stream(propertyNames.spliterator(), false)
        .filter(propertyName -> WORKFLOW_LEVEL_PROPERTY.matcher(propertyName).matches())
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
   * Maps workflow module properties. Whether the configured modules match the
   * modules found in the classpath is validated by the core
   * ({@link MigrationAdapterProperties#validateProperties(List, List)}).
   *
   * @return Map of workflow modules (key = workflow module ID, value = properties)
   */
  private Map<String, WorkflowModuleAdapterProperties> getWorkflowModulesConfigured() {

    return properties
        .workflowModules()
        .entrySet()
        .stream()
        .map(workflowModule -> Map.entry(
            workflowModule.getKey(),
            (WorkflowModuleAdapterProperties) WorkflowModuleAdapterProperties
                .builder()
                .workflowModuleId(workflowModule.getKey())
                .prioritizedAdapters(workflowModule.getValue().prioritizedAdapters().isPresent()
                    ? workflowModule.getValue().prioritizedAdapters().get()
                    : List.of())
                // TODO fill workflows; until implemented, configured workflow-level
                //  properties are rejected by rejectWorkflowLevelConfiguration()
                .workflows(Map.of())
                .adapters(workflowModule
                    .getValue()
                    .adapters()
                    .entrySet()
                    .stream()
                    .map(adapter -> Map.entry(
                        adapter.getKey(),
                        AdapterProperties
                            .builder()
                            .resourcesLocation(adapter.getValue().resourcesLocation())
                            .build()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .build()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

  }

  /**
   * Determine and validate per-adapter deployment-failure policies configured
   * (property <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>).
   *
   * @return Map of policies (key = adapter name, value = policy)
   */
  private Map<String, DeploymentFailurePolicy> getAndValidateDeploymentFailuresConfigured() {

    final var invalidPolicies = properties
        .adapters()
        .entrySet()
        .stream()
        .filter(adapter -> adapter.getValue().deploymentFailure().isPresent())
        .filter(adapter -> {
          try {
            DeploymentFailurePolicy.valueOf(adapter.getValue().deploymentFailure().get().toUpperCase());
            return false;
          } catch (final IllegalArgumentException e) {
            return true;
          }
        })
        .map(adapter -> "'%s' found in '%s.adapters.%s.deployment-failure'"
            .formatted(adapter.getValue().deploymentFailure().get(), PREFIX, adapter.getKey()))
        .sorted()
        .collect(Collectors.joining("\n  "));
    if (!invalidPolicies.isEmpty()) {
      throw new IllegalStateException(
          """
              Properties '%s.adapters.*.deployment-failure' must be one of 'fail' or 'warn'!
              These values are invalid:
                %s"""
              .formatted(PREFIX, invalidPolicies));
    }

    return properties
        .adapters()
        .entrySet()
        .stream()
        .filter(adapter -> adapter.getValue().deploymentFailure().isPresent())
        .map(adapter -> Map.entry(
            adapter.getKey(),
            DeploymentFailurePolicy.valueOf(adapter.getValue().deploymentFailure().get().toUpperCase())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

  }

  /**
   * Determine adapters configured based on the capabilities of VanillaBP adapter
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
    final var adapterTypesProvidedByOtherExtensions = adapterPackagesProvidedByOtherExtensions
        .stream()
        .map(pkg -> pkg.substring(PREFIX_ADAPTER_PACKAGE.length()))
        .toList();
    if (adapterPackagesProvidedByOtherExtensions.isEmpty()) {
      throw new IllegalStateException(
          "No extensions found with capabilities '%s*'! Add Quarkus extensions providing VanillaBP adapters."
              .formatted(PREFIX_ADAPTER_PACKAGE));
    }

    // build result map (key = adapter id, value = adapter type)
    final var result = properties
        .adapters()
        .entrySet()
        .stream()
        // map type property, if not set, to the adapters id
        .map(config -> Map.entry(config.getKey(), config.getValue().type().orElse(config.getKey())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    if (result.isEmpty()) {
      final var missingConfigSections = adapterTypesProvidedByOtherExtensions
          .stream()
          .map(adapter -> "%s.adapters.xxxx.type=%s".formatted(PREFIX, adapter))
          .collect(Collectors.joining("\n  "));
      throw new IllegalStateException(
          """
              No adapters configured! Add properties sections for your BPMS (e.g. xxx) having type set to adapters found in classpath:
                %s"""
              .formatted(missingConfigSections));
    }

    // whether the configured types are actually provided by extensions is
    // validated by the core (MigrationAdapterProperties#validateProperties)

    // validate adapters provided by VanillaBP Quarkus adapter extensions
    final var extensionsWithoutCapability = adaptersFound
        .stream()
        .filter(adapter -> !adapterTypesProvidedByOtherExtensions.contains(adapter))
        .collect(Collectors.joining("\n  "));
    if (!extensionsWithoutCapability.isEmpty()) {
      throw new IllegalStateException(
          """
              Illegal VanillaBP adapter extensions:
                '%s'
              are not matching their extension capabilities!"""
              .formatted(extensionsWithoutCapability));
    }

    // whether configured types actually exist is validated by the core against
    // the capability-derived types (MigrationAdapterProperties#validateProperties)

    return result;

  }

  /**
   * Validates - AFTER the core validation, so configuration typos are reported
   * with the core's guiding messages first - that every VanillaBP adapter
   * extension added to the application is actually configured.
   *
   * @param adaptersFound All adapters found during augmentation
   * @param adapterTypesProvidedByExtensions The capability-derived adapter types
   * @param adapters The configured adapters (key = adapter id, value = type)
   */
  private void validateAllExtensionsConfigured(
      final Collection<String> adaptersFound,
      final List<String> adapterTypesProvidedByExtensions,
      final Map<String, String> adapters) {

    // validate properties of process services against adapters provided by VanillaBP Quarkus adapter extensions
    final var buildItemsNotConfigured = adaptersFound
        .stream()
        .filter(Predicate.not(adapters::containsValue))
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
    final var unconfiguredAdapters = adapterTypesProvidedByExtensions
        .stream()
        .filter(adapter -> !adapters.containsValue(adapter))
        .collect(Collectors.joining(", "));
    if (!unconfiguredAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              No '%s.adapters.*' properties sections having types provided by Quarkus extension!
              Add section section if intended or remove extensions for these types: %s."""
              .formatted(PREFIX, unconfiguredAdapters));
    }

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

    // completeness, duplicates and unknown adapter ids are validated by the core
    // (MigrationAdapterProperties#validateProperties)
    return properties.prioritizedAdapters().orElse(List.of());

  }

}
