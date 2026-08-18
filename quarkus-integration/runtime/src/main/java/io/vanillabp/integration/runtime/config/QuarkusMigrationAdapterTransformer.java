package io.vanillabp.integration.runtime.config;

import static io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties.PREFIX;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.ClasspathFacts;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.support.BpmsAdapters;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import lombok.Builder;

/**
 * Turns {@link QuarkusMigrationAdapterProperties} into
 * {@link MigrationAdapterProperties} using the GENERATED, purely mechanical
 * {@link QuarkusMigrationAdapterPropertiesMapper} (zero validation, zero
 * defaulting) and validates what only Quarkus can know: the consistency of the
 * VanillaBP adapter extensions' capabilities. Everything else - defaulting,
 * guiding messages, the workflow-level rejection and the environment-variable
 * misbinding check - is done ONCE in the core
 * ({@link MigrationAdapterProperties#validateProperties(List, List)} and
 * {@link MigrationAdapterProperties#validateEnvironmentVariableUsage(Iterable)}),
 * so the same configuration yields the same validation outcome on all platforms.
 */
@Builder
public class QuarkusMigrationAdapterTransformer {

  /**
   * Each VanillaBP adapter is a Quarkus extension publishing a Quarkus extension
   * capability with its name prefixed by this prefix. e.g. io.vanillabp.adapter.dummy
   */
  public static final String PREFIX_ADAPTER_PACKAGE = "io.vanillabp.adapter.";

  /**
   * Quarkus' configuration binding derives the keys of a properties map from the
   * property NAMES below its prefix. A section consisting of nothing but its id
   * (YAML <code>&lt;id&gt;:</code> or <code>&lt;id&gt;: {}</code>) contributes no
   * property name and is therefore dropped silently - and spelling it as a property
   * with an empty value (<code>&lt;id&gt;=</code>) is rejected by SmallRye
   * ("does not map to any root"). So unlike on Spring Boot, the id-is-the-type
   * convention ({@link MigrationAdapterProperties#adapterTypes()}) cannot be used
   * with an otherwise empty section here. Since the id never reaches the runtime,
   * this cannot be detected - the developer has to learn it from the message.
   */
  public static final String QUARKUS_EMPTY_SECTION_NOTE = """
      Note for Quarkus: a properties section needs AT LEAST ONE property - a section consisting of its id alone \
      (e.g. '%s.adapters.<id>:' in YAML) contributes no property name and is therefore ignored by the \
      configuration binding. For adapters set 'type=<adapter type>', even if the id already is the type."""
      .formatted(PREFIX);

  /**
   * The properties to transform
   */
  private final QuarkusMigrationAdapterProperties properties;

  /**
   * Raw names of all properties available, including unconverted
   * environment-variable names. Used by the core to detect <code>VANILLABP_*</code>
   * environment variables which were not taken over by the configuration binding.
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

    // determine adapters by examining capabilities of Quarkus extensions available:
    final var adapterTypesProvidedByExtensions = capabilities
        .stream()
        .filter(capability -> capability.startsWith(PREFIX_ADAPTER_PACKAGE))
        .map(pkg -> pkg.substring(PREFIX_ADAPTER_PACKAGE.length()))
        .toList();
    if (adapterTypesProvidedByExtensions.isEmpty()) {
      throw new IllegalStateException(
          """
              No VanillaBP BPMS adapter found in classpath! No Quarkus extension publishing a \
              capability '%s*' is loaded, so there is no BPMS which could run the workflows of a \
              workflow module.%s"""
              .formatted(PREFIX_ADAPTER_PACKAGE, BpmsAdapters.extensionsToAdd()));
    }

    // validate adapters provided by VanillaBP Quarkus adapter extensions
    final var extensionsWithoutCapability = adaptersFound
        .stream()
        .filter(adapter -> !adapterTypesProvidedByExtensions.contains(adapter))
        .collect(Collectors.joining("\n  "));
    if (!extensionsWithoutCapability.isEmpty()) {
      throw new IllegalStateException(
          """
              Illegal VanillaBP adapter extensions:
                '%s'
              are not matching their extension capabilities!"""
              .formatted(extensionsWithoutCapability));
    }

    // purely mechanical copy onto the core model - no validation, no defaulting
    final var result = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    // run the core validation - one validation, in core, identical on all
    // platforms (adapter types are the capability suffixes of the VanillaBP
    // adapter extensions loaded)
    // convention over configuration (story 34): the classpath facts are what the
    // conventions are derived from - a GLOBAL workflow module is one declared by
    // the root application archive (the application IS the workflow module),
    // which decides the conventional resources location
    final var knownWorkflowModules = workflowModulesFound
        .stream()
        .map(workflowModule -> new ClasspathFacts.WorkflowModuleInfo(
            workflowModule.getId(), workflowModule.isGlobal()))
        .toList();
    result.validateProperties(
        new ClasspathFacts(adapterTypesProvidedByExtensions, knownWorkflowModules),
        QUARKUS_EMPTY_SECTION_NOTE);
    if (propertyNames != null) {
      result.validateEnvironmentVariableUsage(propertyNames);
    }

    // Quarkus-specific consistency - AFTER the core validation, so configuration
    // typos are reported with the core's guiding messages first: every VanillaBP
    // adapter extension added to the application has to be configured
    validateAllExtensionsConfigured(adaptersFound, adapterTypesProvidedByExtensions, result.adapterTypes());

    return result;

  }

  /**
   * Validates that every VanillaBP adapter extension added to the application is
   * actually configured.
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

}
