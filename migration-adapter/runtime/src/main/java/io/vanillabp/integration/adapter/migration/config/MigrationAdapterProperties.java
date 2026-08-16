package io.vanillabp.integration.adapter.migration.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementations
   * (properties section <code>vanillabp.outbox</code>).
   */
  @Builder.Default
  private PhaseTwoOutboxProperties outbox = new PhaseTwoOutboxProperties();

  /**
   * Configuration of the default election cache
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} (properties section
   * <code>vanillabp.workflow-adapter-cache</code>).
   */
  @Builder.Default
  private WorkflowAdapterCacheProperties workflowAdapterCache = new WorkflowAdapterCacheProperties();

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
   * The base location BPMN resources of a workflow module are read from when
   * neither an adapter-specific nor the global <code>resources-location</code> is
   * configured - derived from the classpath facts by
   * {@link #normalize(ClasspathFacts)} (story 34's convention). Key is the
   * workflow module ID, value the location WITHOUT the adapter ID (which is
   * appended per adapter, see
   * {@link #getAdapterResourcesLocationsFor(String, String)}).
   */
  @Builder.Default
  private Map<String, List<String>> conventionalResourcesLocations = Map.of();

  /**
   * Applies convention-over-configuration defaults to the bound properties:
   * if exactly one adapter is configured, the property
   * <code>vanillabp.prioritized-adapters</code> may be omitted and defaults to that
   * adapter. Invoked by {@link #validateProperties(List, List)}; has to be invoked
   * explicitly if properties objects are used without running validation.
   * <p>
   * The classpath-based derivations (adapter sections, workflow-module sections,
   * resources locations) need facts about the application and are therefore
   * applied by {@link #normalize(ClasspathFacts)} only.
   */
  public void normalize() {

    if (getPrioritizedAdapters().isEmpty() && (adapters.size() == 1)) {
      setPrioritizedAdapters(List.copyOf(adapters.keySet()));
    }

  }

  /**
   * Convention over configuration (story 34): derives everything the platform
   * already knows from the classpath, so an application only configures what
   * DEVIATES from the convention. Runs BEFORE the validation - a derived entry is
   * validated exactly like a hand-written one.
   * <ol>
   * <li><b>Adapter sections:</b> nothing configured and exactly ONE adapter type in
   * the classpath &rarr; that type becomes the one adapter (id = type). Several
   * types are never guessed - the validation asks for
   * <code>vanillabp.prioritized-adapters</code>. An id listed in
   * <code>prioritized-adapters</code> without a section is derived if it IS an
   * adapter type; a CUSTOM id can never be derived (it carries no information
   * about its type - the developer has to write <code>type</code>).</li>
   * <li><b>Workflow-module sections:</b> a module found in the classpath without a
   * section gets an empty one - a module needs no configuration any more.</li>
   * <li><b>Resources locations:</b> the location BPMN is read from follows the
   * convention documented at
   * {@link #getAdapterResourcesLocationsFor(String, String)}.</li>
   * </ol>
   *
   * @param facts What the platform knows about the application without any
   *          property (see {@link ClasspathFacts})
   */
  public void normalize(
      final ClasspathFacts facts) {

    deriveAdapterSections(facts);

    normalize();

    deriveWorkflowModuleSections(facts);
    deriveConventionalResourcesLocations(facts);

  }

  /**
   * Derives the adapter sections which carry no information beyond what the
   * classpath already tells (see {@link #normalize(ClasspathFacts)}).
   */
  private void deriveAdapterSections(
      final ClasspathFacts facts) {

    final var adapterTypes = facts.adapterTypes();

    if (adapters.isEmpty() && (adapterTypes.size() == 1)) {
      // the single adapter dependency IS the configuration
      final var adapterType = adapterTypes.getFirst();
      adapters = new LinkedHashMap<>(Map.of(adapterType, AdapterConfigProperties.ofType(adapterType)));
      return;
    }

    // ids named in 'prioritized-adapters' which ARE adapter types need no section
    final var derivableIds = getPrioritizedAdapters()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .filter(adapterTypes::contains)
        .distinct()
        .toList();
    if (derivableIds.isEmpty()) {
      return;
    }
    final var derived = new LinkedHashMap<>(adapters);
    derivableIds.forEach(adapterId -> derived.put(adapterId, AdapterConfigProperties.ofType(adapterId)));
    adapters = derived;

  }

  /**
   * Derives an empty section for every workflow module found in the classpath
   * which has none (see {@link #normalize(ClasspathFacts)}).
   */
  private void deriveWorkflowModuleSections(
      final ClasspathFacts facts) {

    final var missingSections = facts
        .workflowModuleIds()
        .stream()
        .filter(workflowModuleId -> !workflowModules.containsKey(workflowModuleId))
        .toList();
    if (missingSections.isEmpty()) {
      return;
    }
    final var derived = new LinkedHashMap<>(workflowModules);
    missingSections.forEach(
        workflowModuleId -> derived.put(workflowModuleId, new WorkflowModuleAdapterProperties()));
    workflowModules = derived;

  }

  /**
   * Derives the conventional resources location of every workflow module found in
   * the classpath (see {@link #getAdapterResourcesLocationsFor(String, String)} for
   * the convention itself).
   */
  private void deriveConventionalResourcesLocations(
      final ClasspathFacts facts) {

    final var modules = facts.workflowModules();
    final var singleModuleOfMainArtifact = (modules.size() == 1) && modules
        .getFirst()
        .fromMainArtifact();

    final var locations = new LinkedHashMap<String, List<String>>();
    modules.forEach(
        module -> locations.put(
            module.id(),
            singleModuleOfMainArtifact
                // the application IS the workflow module - but a module tested inside
                // its own Maven module is the main artifact as well, and its files sit
                // where the packaged application needs them: below the module ID. Both
                // are searched, the module's own location first (story 68; the module's
                // configuration files are found in both places for the same reason)
                ? List.of(
                    "classpath*:%s/processes".formatted(module.id()),
                    "classpath*:processes")
                : List.of("classpath*:%s/processes".formatted(module.id()))));
    conventionalResourcesLocations = locations;

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

  /**
   * A workflow module id must not contain the separator VanillaBP uses to scope
   * identifiers ({@link io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport#SEPARATOR})
   * - a prefixed identifier could no longer be read back reliably. Only checked
   * when prefixing is configured ANYWHERE, so existing applications which do not
   * use it are not affected.
   *
   * @throws IllegalStateException Naming the offending module ids and the separator
   */
  public void validateWorkflowModuleIdsAgainstPrefixing() {

    if (!isPrefixingConfiguredAnywhere()) {
      return;
    }
    final var separator = io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.SEPARATOR;
    final var offending = workflowModules
        .keySet()
        .stream()
        .filter(workflowModuleId -> workflowModuleId.contains(separator))
        .sorted()
        .collect(Collectors.joining("', '", "'", "'"));
    if (offending.equals("''")) {
      return;
    }
    throw new IllegalStateException(
        ("The workflow module id(s) %s contain '%s'! VanillaBP uses it to separate a prefix from "
            + "the identifier it scopes (name-clash-avoidance 'use-prefix' is configured), so a "
            + "prefixed identifier could no longer be read back. Rename the workflow module(s) or "
            + "choose another name-clash-avoidance mode.")
            .formatted(offending, separator));

  }

  /**
   * Whether {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}
   * is configured at any level for any adapter.
   */
  private boolean isPrefixingConfiguredAnywhere() {

    final var usePrefix = io.vanillabp.integration.adapter.spi.NameClashAvoidance.USE_PREFIX;
    if (adapters
        .values()
        .stream()
        .anyMatch(adapter -> adapter.getNameClashAvoidance() == usePrefix)) {
      return true;
    }
    return workflowModules
        .values()
        .stream()
        .anyMatch(module -> anyUsesPrefix(module.getAdapters()) || module
            .getWorkflows()
            .values()
            .stream()
            .anyMatch(workflow -> anyUsesPrefix(workflow.getAdapters())));

  }

  private static boolean anyUsesPrefix(
      final Map<String, ? extends AdapterProperties> adaptersOfLevel) {

    return (adaptersOfLevel != null) && adaptersOfLevel
        .values()
        .stream()
        .anyMatch(
            adapter -> adapter
                .getNameClashAvoidance() == io.vanillabp.integration.adapter.spi.NameClashAvoidance.USE_PREFIX);

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
   * Resolves an adapter-scoped property with most-specific-wins semantics across the
   * four levels an adapter-scoped property may be configured at:
   *
   * <pre>
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.adapters.&lt;id&gt;.&lt;key&gt;  (most specific)
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;id&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.&lt;key&gt;
   * vanillabp.adapters.&lt;id&gt;.&lt;key&gt;                                                              (least specific)
   * </pre>
   *
   * The levels are walked from most to least specific and the first non-null value
   * the extractor returns wins (same precedent as
   * {@link #getPrioritizedAdaptersFor(String, String)}). Levels whose scope ID is
   * <code>null</code> or not configured are skipped, so the resolver may be used
   * for adapter-, module-, workflow- and task-scoped lookups alike.
   * <p>
   * A scope which is "present but empty" is treated exactly like a missing scope
   * (the extractor returns <code>null</code> for both) - scope materialization
   * differs per binder and must not influence the resolution.
   *
   * @param <T> The type of the resolved value
   * @param workflowModuleId The workflow module ID or <code>null</code>
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskId The task ID (task definition) or <code>null</code>
   * @param adapterId The adapter ID
   * @param valueExtractor Extracts the value from a level's adapter-scoped
   *          properties; has to return <code>null</code> if the value is not set at
   *          that level
   * @return The most specific value configured or <code>null</code>
   */
  public <T> T resolveForAdapter(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId,
      final Function<AdapterProperties, T> valueExtractor) {

    final var workflowModule = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (workflowModule != null) && (bpmnProcessId != null)
        ? workflowModule.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskId != null)
        ? workflow.getTasks().get(taskId)
        : null;

    if (task != null) {
      final var value = extractForAdapter(task.getAdapters(), adapterId, valueExtractor);
      if (value != null) {
        return value;
      }
    }
    if (workflow != null) {
      final var value = extractForAdapter(workflow.getAdapters(), adapterId, valueExtractor);
      if (value != null) {
        return value;
      }
    }
    if (workflowModule != null) {
      final var value = extractForAdapter(workflowModule.getAdapters(), adapterId, valueExtractor);
      if (value != null) {
        return value;
      }
    }
    return extractForAdapter(adapters, adapterId, valueExtractor);

  }

  private static <T> T extractForAdapter(
      final Map<String, ? extends AdapterProperties> adaptersOfLevel,
      final String adapterId,
      final Function<AdapterProperties, T> valueExtractor) {

    final var adapter = adaptersOfLevel.get(adapterId);
    return adapter != null
        ? valueExtractor.apply(adapter)
        : null;

  }

  /**
   * Whether the given adapter is the FIRST entry of the prioritized-adapters list at
   * ANY level (globally, of any workflow module or of any workflow) - i.e. whether
   * new workflows may be started via this adapter anywhere. Used to decide how
   * strictly an adapter's own startup validation may treat configuration defects:
   * an adapter that is nowhere first may honor its
   * <code>deployment-failure: warn</code> policy and keep the application booting
   * (migration scenario: the OLD BPMS is intentionally degraded), whereas an
   * adapter that is first anywhere always fails the boot on a genuine defect -
   * new workflows could not be started otherwise.
   *
   * @param adapterId The adapter ID
   * @return Whether the adapter is first priority at any level
   */
  public boolean isFirstPriorityAnywhere(
      final String adapterId) {

    if (isFirstOf(getPrioritizedAdapters(), adapterId)) {
      return true;
    }
    return workflowModules
        .values()
        .stream()
        .anyMatch(workflowModule -> isFirstOf(workflowModule.getPrioritizedAdapters(), adapterId) || workflowModule
            .getWorkflows()
            .values()
            .stream()
            .anyMatch(workflow -> isFirstOf(workflow.getPrioritizedAdapters(), adapterId)));

  }

  private static boolean isFirstOf(
      final List<String> prioritizedAdapters,
      final String adapterId) {

    return !prioritizedAdapters.isEmpty() && prioritizedAdapters.getFirst().equals(adapterId);

  }

  /**
   * Whether the given adapter is the FIRST entry of the prioritized-adapters list
   * effective for the given workflow module or for ANY workflow of that module -
   * i.e. whether new workflows of this module may be started via this adapter.
   * Used by the deployment-failure policy: a deployment failure of an adapter
   * being first priority for at least one workflow always fails the boot, even if
   * the adapter is not the module's first-priority adapter (starting that workflow
   * would fail at runtime otherwise).
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @return Whether the adapter is first priority for the module or any of its
   *         workflows
   */
  public boolean isFirstPriorityFor(
      final String workflowModuleId,
      final String adapterId) {

    if (isFirstOf(getPrioritizedAdaptersFor(workflowModuleId), adapterId)) {
      return true;
    }
    final var workflowModule = workflowModules.get(workflowModuleId);
    if (workflowModule == null) {
      return false;
    }
    return workflowModule
        .getWorkflows()
        .keySet()
        .stream()
        .anyMatch(bpmnProcessId -> isFirstOf(
            getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId),
            adapterId));

  }

  /**
   * The adapters which have to receive the given workflow module's deployment: the
   * union of the module's effective prioritized-adapters list and every adapter
   * named in a workflow-level <code>prioritized-adapters</code> override of that
   * module. BPMS election is process-granular while deployment is file-granular -
   * an adapter prioritized for a single workflow only still needs the module's
   * resources deployed, otherwise starting that workflow fails at runtime. Order:
   * the module-level list first, followed by adapters named at the workflow level
   * only.
   *
   * @param workflowModuleId The workflow module ID
   * @return The adapter IDs to deploy the module's resources to
   */
  public List<String> getDeploymentAdaptersFor(
      final String workflowModuleId) {

    final var deploymentAdapters = new LinkedList<>(getPrioritizedAdaptersFor(workflowModuleId));
    final var workflowModule = workflowModules.get(workflowModuleId);
    if (workflowModule != null) {
      workflowModule
          .getWorkflows()
          .values()
          .stream()
          .flatMap(workflow -> workflow.getPrioritizedAdapters().stream())
          .distinct()
          .filter(adapterId -> !deploymentAdapters.contains(adapterId))
          .forEach(deploymentAdapters::add);
    }
    return deploymentAdapters;

  }

  private Stream<String> unknownAdapterKeys(
      final Map<String, ? extends AdapterProperties> adaptersOfLevel,
      final String keyPrefixOfLevel) {

    return adaptersOfLevel
        .keySet()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .map(adapterId -> "%s.adapters.%s".formatted(keyPrefixOfLevel, adapterId));

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
   * Provides the resources location according to the given properties, resolved in
   * this order (story 34):
   * <ol>
   * <li>the adapter-specific location
   * <code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.resources-location</code>,</li>
   * <li>the global <code>vanillabp.resources-location</code> for BPMN which is NOT
   * specific to a BPMS,</li>
   * <li>the <b>convention</b> - what the classpath facts imply:
   * <table border="1">
   * <caption>Conventional locations</caption>
   * <tr><th>Situation</th><th>Location</th></tr>
   * <tr><td>several workflow modules, or a single one shipped as its own
   * artifact</td>
   * <td><code>classpath*:&lt;workflow-module-id&gt;/processes/&lt;adapter-id&gt;</code></td></tr>
   * <tr><td>exactly one workflow module, declared by the application's MAIN
   * artifact</td>
   * <td><code>classpath*:&lt;workflow-module-id&gt;/processes/&lt;adapter-id&gt;</code>,
   * and <code>classpath*:processes/&lt;adapter-id&gt;</code> if the first holds
   * nothing</td></tr>
   * </table>
   * A conventional location is adapter-specific like a configured one.</li>
   * </ol>
   * The locations are returned in the order they are searched, and the first one
   * holding BPMN files wins (see {@code DeploymentService}) - so a process is never
   * deployed twice. A configured location is always the only one; the convention
   * names two where the application IS the workflow module, because a module tested
   * inside its own Maven module is the main artifact as well while its files sit
   * below the module ID (story 68).
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @return The locations to search, never empty, each carrying whether it contains
   *         VanillaBP BPMN (true) or BPMN specific to the target BPMS (false)
   */
  public List<ResourcesLocation> getAdapterResourcesLocationsFor(
      final String workflowModuleId,
      final String adapterId) {

    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule != null) {
      final var adapter = workflowModule.getAdapters().get(adapterId);
      if ((adapter != null) && (adapter.getResourcesLocation() != null) && !adapter.getResourcesLocation().isBlank()) {
        return List.of(new ResourcesLocation(adapter.getResourcesLocation(), false));
      }
    }

    final var globalResourcesLocation = getResourcesLocation();
    if ((globalResourcesLocation != null) && !globalResourcesLocation.isBlank()) {
      return List.of(new ResourcesLocation(globalResourcesLocation, true));
    }

    final var conventionalLocations = conventionalResourcesLocations.get(workflowModuleId);
    if ((conventionalLocations != null) && !conventionalLocations.isEmpty()) {
      return conventionalLocations
          .stream()
          .map(conventionalLocation -> new ResourcesLocation(
              "%s/%s".formatted(conventionalLocation, adapterId), false))
          .toList();
    }

    throw new IllegalStateException(
        """
            Neither property '%s.workflow-modules.%s.adapters.%s.resources-location' for resources specific to the BPMS
            nor property '%s.resources-location' for VanillaBP resources (not specific to the BPMS) is set, and the
            workflow module '%s' is not known in the classpath, so no location can be derived by convention!

            If using the first option then the location needs to be specific to the adapter in order to avoid future
            problems once you wish to migrate to another adapter. Sample: 'classpath*:/workflow-resources/%s'"""
            .formatted(PREFIX, workflowModuleId, adapterId, PREFIX, workflowModuleId, adapterId));

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

    validateProperties(adaptersLoaded, knownWorkflowModuleIds, null);

  }

  /**
   * Appends a platform-specific note to a guiding message, if there is one.
   *
   * @param message The platform-neutral message
   * @param platformConfigurationNote The note or null
   * @return The message, followed by the note in an own paragraph
   */
  private static String appendPlatformNote(
      final String message,
      final String platformConfigurationNote) {

    if ((platformConfigurationNote == null) || platformConfigurationNote.isBlank()) {
      return message;
    }
    return "%s\n%s".formatted(message, platformConfigurationNote);

  }

  /**
   * Validates the configuration. Identical on all platforms, except for the
   * <code>platformConfigurationNote</code>: platforms whose configuration binding
   * behaves in a way a developer cannot guess from the properties alone pass a
   * sentence naming that behavior, which is appended to those messages a developer
   * could otherwise misread (see
   * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#adapterTypes()}
   * for the id-is-the-type convention this concerns).
   *
   * @param adaptersLoaded The adapter types found in the classpath
   * @param knownWorkflowModuleIds The workflow module IDs found in the classpath
   * @param platformConfigurationNote A platform-specific note appended to
   *     "nothing configured" messages, or null
   */
  public void validateProperties(
      final List<String> adaptersLoaded,
      final List<String> knownWorkflowModuleIds,
      final String platformConfigurationNote) {

    validateProperties(
        ClasspathFacts.of(adaptersLoaded, knownWorkflowModuleIds),
        platformConfigurationNote);

  }

  /**
   * Validates the configuration, having derived everything derivable from the
   * classpath facts before (see {@link #normalize(ClasspathFacts)}) - the
   * validation rules are identical for configured and derived entries.
   *
   * @param facts What the platform knows about the application without any
   *          property
   * @param platformConfigurationNote A platform-specific note appended to
   *     "nothing configured" messages, or null
   */
  public void validateProperties(
      final ClasspathFacts facts,
      final String platformConfigurationNote) {

    final var adaptersLoaded = facts.adapterTypes();
    final var knownWorkflowModuleIds = facts.workflowModuleIds();

    normalize(facts);
    validateAndLink();
    validateWorkflowModuleIdsAgainstPrefixing();
    if (workflowAdapterCache == null) {
      // a binder mapping an absent section onto null must not cost the defaults
      workflowAdapterCache = new WorkflowAdapterCacheProperties();
    }
    workflowAdapterCache.validate();

    if (knownWorkflowModuleIds.isEmpty()) {
      throw new IllegalStateException("No workflow-modules where given!");
    }

    if (adapters.isEmpty() && adaptersLoaded.isEmpty()) {
      throw new IllegalStateException(
          "No adapters configured and none found in classpath! Add a dependency providing a VanillaBP adapter.");
    }

    if (adapters.isEmpty()) {
      // several adapter types in the classpath (exactly one would have been derived
      // by normalize): which one starts new workflows - and in which order existing
      // workflows are looked up - cannot be guessed
      // deterministic order: the classpath order of extensions/beans is not stable
      final var sortedAdapterTypes = adaptersLoaded
          .stream()
          .sorted()
          .toList();
      throw new IllegalStateException(
          appendPlatformNote(
              """
                  Several VanillaBP adapters were found in classpath:
                    %s
                  Name the order in which they are used by the property '%s.prioritized-adapters' - the first one \
                  starts new workflows, the others are asked for workflows started earlier (BPMS migration).
                  Sample:
                    %s.prioritized-adapters:
                  %s
                  An adapter id which IS an adapter type needs no further configuration; a custom id needs a section \
                  '%s.adapters.<id>.type=<adapter type>'."""
                  .formatted(
                      String.join("\n  ", sortedAdapterTypes),
                      PREFIX,
                      PREFIX,
                      sortedAdapterTypes
                          .stream()
                          .map("    - %s"::formatted)
                          .collect(Collectors.joining("\n")),
                      PREFIX),
              platformConfigurationNote));
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

    // a workflow module found in the classpath needs NO configuration any more
    // (story 34): normalize(facts) derived an empty section for it, and its BPMN
    // resources are found by the resources-location convention

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

    // adapter entries which are never used (V1-style check): every key under an
    // 'adapters.<id>' section of any level (workflow module, workflow, task) has
    // to reference a configured adapter id
    final var unusedModuleAdapterEntries = getWorkflowModules()
        .values()
        .stream()
        .flatMap(workflowModule -> {
          final var modulePrefix = "%s.workflow-modules.%s".formatted(PREFIX, workflowModule.workflowModuleId);
          return Stream.concat(
              unknownAdapterKeys(workflowModule.getAdapters(), modulePrefix),
              workflowModule
                  .getWorkflows()
                  .values()
                  .stream()
                  .flatMap(workflow -> {
                    final var workflowPrefix = "%s.workflows.%s".formatted(modulePrefix, workflow.getBpmnProcessId());
                    return Stream.concat(
                        unknownAdapterKeys(workflow.getAdapters(), workflowPrefix),
                        workflow
                            .getTasks()
                            .entrySet()
                            .stream()
                            .flatMap(task -> unknownAdapterKeys(
                                task.getValue().getAdapters(),
                                "%s.tasks.%s".formatted(workflowPrefix, task.getKey()))));
                  }));
        })
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
      // only CONFIGURED adapter-specific locations are worth the hint - a location
      // derived by convention is adapter-specific by construction (story 34)
      final var specificBpmnResources = workflowModules
          .entrySet()
          .stream()
          .filter(entry -> {
            final var adapter = entry
                .getValue()
                .getAdapters()
                .get(adapterId);
            return (adapter != null) && (adapter.getResourcesLocation() != null) && !adapter.getResourcesLocation()
                .isBlank();
          })
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
          prioritizedAdaptersOfModule
              .forEach(adapterId -> getAdapterResourcesLocationsFor(workflowModuleId, adapterId));
          getBpmnProcessIdsForWorkflowModule(workflowModuleId)
              .forEach(
                  bpmnProcessId -> {
                    final var prioritizedAdapters = getPrioritizedAdaptersFor(
                        workflowModuleId,
                        bpmnProcessId
                    );
                    prioritizedAdapters.forEach(adapterId -> getAdapterResourcesLocationsFor(
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
