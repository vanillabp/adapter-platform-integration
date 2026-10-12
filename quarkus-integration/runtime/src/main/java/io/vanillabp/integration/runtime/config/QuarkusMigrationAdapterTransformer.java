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
 * {@link MigrationAdapterProperties} using the hand-written, purely mechanical
 * {@link QuarkusMigrationAdapterPropertiesMapper} (zero validation, zero
 * defaulting) and validates what only Quarkus can know: the consistency of the
 * VanillaBP adapter extensions' capabilities. Everything else - defaulting,
 * guiding messages, the workflow-level rejection and the environment-variable
 * misbinding check - is done ONCE in the core
 * ({@link MigrationAdapterProperties#validateProperties(List, List)} and
 * {@link MigrationAdapterProperties#validateEnvironmentVariableUsage(Iterable)}),
 * so the same configuration yields the same validation outcome on all platforms.
 */
public class QuarkusMigrationAdapterTransformer {

  /**
   * Each VanillaBP adapter is a Quarkus extension. It publishes a Quarkus extension
   * capability whose name starts with this prefix, e.g. io.vanillabp.adapter.dummy.
   */
  public static final String PREFIX_ADAPTER_PACKAGE = "io.vanillabp.adapter.";

  /**
   * The configuration binding of Quarkus takes the keys of a properties map from the
   * property NAMES below its prefix. A section with nothing but its id (YAML
   * <code>&lt;id&gt;:</code> or <code>&lt;id&gt;: {}</code>) adds no property name. So it is
   * dropped without a word. Writing it as a property with an empty value
   * (<code>&lt;id&gt;=</code>) does not help either, because SmallRye refuses that ("does not
   * map to any root"). So unlike on Spring Boot, the convention "the id is the type"
   * ({@link MigrationAdapterProperties#adapterTypes()}) does not work here with an otherwise
   * empty section. The id never reaches the runtime, so this cannot be detected. The
   * developer has to learn it from the message.
   */
  public static final String QUARKUS_EMPTY_SECTION_NOTE = """
      Note for Quarkus: a properties section needs AT LEAST ONE property - a section consisting of its id alone \
      (e.g. '%s.adapters.<id>:' in YAML) contributes no property name and is therefore ignored by the \
      configuration binding. For adapters set 'type=<adapter type>', even if the id already is the type."""
      .formatted(PREFIX);

  private final QuarkusMigrationAdapterProperties properties;

  /**
   * The raw names of all available properties, including environment variable names which
   * are not converted. The core uses them to find <code>VANILLABP_*</code> environment
   * variables which the configuration binding did not take over.
   */
  private final Iterable<String> propertyNames;

  private final Collection<String> capabilities;

  /**
   * The recorder which runs while the application starts creates it through the generated
   * builder. Everything it needs is known at that moment and never changes later. So the
   * three fields are final, and the object is used once and then dropped.
   *
   * @param properties What SmallRye bound below <code>vanillabp.</code>
   * @param propertyNames Every property name the configuration knows, unconverted
   * @param capabilities The capabilities of the Quarkus extensions of this application. They
   *          tell which adapters are on the classpath
   */
  @Builder
  QuarkusMigrationAdapterTransformer(
      final QuarkusMigrationAdapterProperties properties,
      final Iterable<String> propertyNames,
      final Collection<String> capabilities) {

    this.properties = properties;
    this.propertyNames = propertyNames;
    this.capabilities = capabilities;

  }

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

    // Find the adapters through the capabilities of the available Quarkus extensions.
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

    // Check the adapters which the VanillaBP Quarkus adapter extensions provide.
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

    // Only copy the values onto the core model. No checks, no defaults.
    final var result = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    // Run the core's checks. There is one set of checks, in the core, the same on all
    // platforms. The adapter types are the capability suffixes of the loaded VanillaBP
    // adapter extensions.
    // Convention over configuration: the conventions come from the classpath facts. A
    // GLOBAL workflow module is one which the root application archive declares (the
    // application IS the workflow module). This decides the default resources location.
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

    // A check only Quarkus needs: every VanillaBP adapter extension added to the
    // application has to be configured. It runs AFTER the core's checks. So typos in the
    // configuration get the core's guiding messages first.
    validateAllExtensionsConfigured(adaptersFound, adapterTypesProvidedByExtensions, result.adapterTypes());

    return result;

  }

  /**
   * Checks that every VanillaBP adapter extension added to the application is really
   * configured.
   *
   * @param adaptersFound All adapters found during augmentation
   * @param adapterTypesProvidedByExtensions The capability-derived adapter types
   * @param adapters The configured adapters (key = adapter id, value = type)
   */
  private void validateAllExtensionsConfigured(
      final Collection<String> adaptersFound,
      final List<String> adapterTypesProvidedByExtensions,
      final Map<String, String> adapters) {

    // every adapter type which a VanillaBP Quarkus adapter extension brings needs a configured adapter of that type
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

    // Look for adapters which have no section in the properties.
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
