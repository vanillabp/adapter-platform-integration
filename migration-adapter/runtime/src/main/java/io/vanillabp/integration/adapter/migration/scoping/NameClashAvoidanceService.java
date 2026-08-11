package io.vanillabp.integration.adapter.migration.scoping;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * THE implementation of the name-clash-avoidance model (story 35): resolving the
 * mode and composing the identifiers a BPMS sees. BPMS-neutral by design - an
 * adapter decides only WHERE to apply the results (its model, its commands).
 *
 * <h2>Resolution</h2>
 *
 * The mode is an ADAPTER-SCOPED property, so it is resolved most-specific-wins
 * across workflow &gt; workflow module &gt; adapter
 * ({@link MigrationAdapterProperties#resolveForAdapter}) - which is what allows two
 * adapter ids to carry DIFFERENT modes for the same workflow module, the basis of
 * the migration path documented on {@link NameClashAvoidance}. Without any
 * configuration the ADAPTER's default applies
 * ({@link AdapterDeploymentService#defaultNameClashAvoidance()}):
 * {@link NameClashAvoidance#BY_ADAPTER} - VanillaBP 1's behavior - for a BPMS which
 * isolates out of the box, {@link NameClashAvoidance#NONE} for one which has to be
 * set up for it first (Camunda 8 rejects tenant ids unless multi-tenancy is enabled).
 * A resolved {@link NameClashAvoidance#NONE} is therefore reported once per workflow
 * module and adapter by the adapter's own WARN
 * ({@link AdapterDeploymentService#warnAboutUnscopedIdentifiers(String, boolean)}) -
 * it protects nothing, and only the adapter knows what its BPMS offers instead.
 *
 * <h2>Composition and its inverse</h2>
 *
 * Outbound identifiers are always composed from KNOWN parts, and inbound ones are
 * stripped by matching a KNOWN prefix - never by searching for the first
 * {@link NameClashAvoidanceSupport#SEPARATOR}. The separator is therefore a
 * readability choice; what protects correctness is
 * {@link #validateNoCollidingProcessIds}.
 */
public class NameClashAvoidanceService implements NameClashAvoidanceSupport {

  private final MigrationAdapterProperties properties;

  /**
   * The adapters' deployment services, resolved LAZILY: every adapter receives this
   * service, so it has to be constructible before any adapter exists. The result is
   * cached once it is non-empty (an empty resolution means the adapter beans were
   * not created yet).
   */
  private final Supplier<Collection<AdapterDeploymentService<?, ?>>> deploymentServices;

  private volatile Map<String, AdapterDeploymentService<?, ?>> deploymentServicesByAdapterId;

  /**
   * The (workflow module, adapter) pairs already reported as unscoped - the mode is
   * resolved on every runtime boundary, the WARN belongs to startup.
   */
  private final Set<String> unscopedReported = ConcurrentHashMap.newKeySet();

  /**
   * Without the adapters' deployment services every adapter's default is
   * {@link NameClashAvoidance#BY_ADAPTER} and no adapter can report an unscoped
   * workflow module - for tests and for platforms not passing them.
   *
   * @param properties The VanillaBP configuration
   */
  public NameClashAvoidanceService(
      final MigrationAdapterProperties properties) {

    this(properties, List::of);

  }

  /**
   * @param properties The VanillaBP configuration
   * @param deploymentServices The adapters' deployment services, asked for their
   *          default mode and for reporting a workflow module whose identifiers are
   *          not scoped. Invoked on first use, never during construction.
   */
  public NameClashAvoidanceService(
      final MigrationAdapterProperties properties,
      final Supplier<Collection<AdapterDeploymentService<?, ?>>> deploymentServices) {

    this.properties = properties;
    this.deploymentServices = deploymentServices;

  }

  @Override
  public NameClashAvoidance modeFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var configured = properties != null
        ? properties.resolveForAdapter(
            workflowModuleId,
            bpmnProcessId,
            null,
            adapterId,
            AdapterProperties::getNameClashAvoidance)
        : null;
    final var mode = configured != null
        ? configured
        : defaultModeFor(adapterId);
    if (mode == NameClashAvoidance.NONE) {
      reportUnscopedIdentifiers(adapterId, workflowModuleId, configured == null);
    }
    return mode;

  }

  /**
   * The mode applying to the given adapter without any configuration - the adapter's
   * own default, {@link NameClashAvoidance#BY_ADAPTER} for an adapter which is
   * unknown here (see {@link #deploymentServices}).
   */
  private NameClashAvoidance defaultModeFor(
      final String adapterId) {

    final var deploymentService = deploymentServiceOf(adapterId);
    final var adapterDefault = deploymentService != null
        ? deploymentService.defaultNameClashAvoidance()
        : null;
    return adapterDefault != null
        ? adapterDefault
        : NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Lets the adapter report the workflow module as unscoped, once per workflow module
   * and adapter id. Resolving a mode without a workflow module (e.g. while comparing
   * two adapter instances) reports nothing - the per-module resolutions of the
   * deployment do.
   */
  private void reportUnscopedIdentifiers(
      final String adapterId,
      final String workflowModuleId,
      final boolean fromDefault) {

    if ((workflowModuleId == null) || (adapterId == null)) {
      return;
    }
    final var deploymentService = deploymentServiceOf(adapterId);
    if (deploymentService == null) {
      return;
    }
    if (!unscopedReported.add("%s@%s".formatted(workflowModuleId, adapterId))) {
      return;
    }
    deploymentService.warnAboutUnscopedIdentifiers(workflowModuleId, fromDefault);

  }

  private AdapterDeploymentService<?, ?> deploymentServiceOf(
      final String adapterId) {

    var byAdapterId = deploymentServicesByAdapterId;
    if (byAdapterId == null) {
      synchronized (this) {
        byAdapterId = deploymentServicesByAdapterId;
        if (byAdapterId == null) {
          final var collected = new LinkedHashMap<String, AdapterDeploymentService<?, ?>>();
          final var resolved = deploymentServices.get();
          if (resolved != null) {
            resolved
                .stream()
                .filter(java.util.Objects::nonNull)
                .forEach(service -> collected.putIfAbsent(service.getAdapterId(), service));
          }
          byAdapterId = collected;
          if (!collected.isEmpty()) {
            // an empty result means the adapters were not created yet - ask again
            deploymentServicesByAdapterId = collected;
          }
        }
      }
    }
    return byAdapterId.get(adapterId);

  }

  /**
   * Whether task definitions are additionally scoped by their BPMN process ID
   * (default <code>true</code>, see
   * {@link AdapterProperties#getPrefixTaskDefinitionsPerProcess()}).
   */
  private boolean scopeTaskDefinitionsPerProcess(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (properties == null) {
      return true;
    }
    final var configured = properties.resolveForAdapter(
        workflowModuleId,
        bpmnProcessId,
        null,
        adapterId,
        AdapterProperties::getPrefixTaskDefinitionsPerProcess);
    return configured == null || configured.booleanValue();

  }

  private boolean prefixes(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    return modeFor(workflowModuleId, bpmnProcessId, adapterId) == NameClashAvoidance.USE_PREFIX;

  }

  @Override
  public String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if ((bpmnProcessId == null) || !prefixes(workflowModuleId, bpmnProcessId, adapterId)) {
      return bpmnProcessId;
    }
    return join(workflowModuleId, bpmnProcessId);

  }

  @Override
  public String scopedIdentifier(
      final String workflowModuleId,
      final String identifier,
      final String adapterId) {

    if ((identifier == null) || !prefixes(workflowModuleId, null, adapterId)) {
      return identifier;
    }
    return join(workflowModuleId, identifier);

  }

  @Override
  public String scopedTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    if ((taskDefinition == null) || !prefixes(workflowModuleId, bpmnProcessId, adapterId)) {
      return taskDefinition;
    }
    return scopeTaskDefinitionsPerProcess(workflowModuleId, bpmnProcessId, adapterId)
        ? join(workflowModuleId, bpmnProcessId, taskDefinition)
        : join(workflowModuleId, taskDefinition);

  }

  @Override
  public String plainProcessId(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String adapterId) {

    if ((scopedBpmnProcessId == null) || !prefixes(workflowModuleId, null, adapterId)) {
      return scopedBpmnProcessId;
    }
    return stripKnownPrefix(scopedBpmnProcessId, join(workflowModuleId, ""));

  }

  @Override
  public String plainIdentifier(
      final String workflowModuleId,
      final String scopedIdentifier,
      final String adapterId) {

    return plainProcessId(workflowModuleId, scopedIdentifier, adapterId);

  }

  @Override
  public String plainTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition,
      final String adapterId) {

    if ((scopedTaskDefinition == null) || !prefixes(workflowModuleId, bpmnProcessId, adapterId)) {
      return scopedTaskDefinition;
    }
    final var prefix = scopeTaskDefinitionsPerProcess(workflowModuleId, bpmnProcessId, adapterId)
        ? join(workflowModuleId, bpmnProcessId, "")
        : join(workflowModuleId, "");
    return stripKnownPrefix(scopedTaskDefinition, prefix);

  }

  @Override
  public String tenantIdFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String configuredTenantId) {

    if (modeFor(workflowModuleId, bpmnProcessId, adapterId) != NameClashAvoidance.BY_ADAPTER) {
      // NONE: no tenant at all; USE_PREFIX: the prefix IS the isolation - using a
      // tenant on top would defeat the purpose (BPMS are licensed per tenant)
      return null;
    }
    return (configuredTenantId != null) && !configuredTenantId.isBlank()
        ? configuredTenantId
        : workflowModuleId;

  }

  @Override
  public void validateNativeIsolationSupported(
      final String adapterId,
      final String workflowModuleId,
      final String bpmsDescription) {

    if (workflowModuleId != null) {
      // the mode is resolvable per workflow module, so check the one being deployed
      if (modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.BY_ADAPTER) {
        return;
      }
      throw new IllegalStateException(
          """
              %s has no isolation mechanism of its own, so the name-clash-avoidance mode '%s' \
              cannot be served by adapter '%s' - but it applies to workflow module '%s'%s. Choose \
              explicitly:
                vanillabp.adapters.%s.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
                vanillabp.adapters.%s.name-clash-avoidance: none         # your identifiers are unique already
              The same key may be set per workflow module and workflow \
              (vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance)."""
              .formatted(
                  capitalize(bpmsDescription),
                  NameClashAvoidance.BY_ADAPTER.name().toLowerCase().replace('_', '-'),
                  adapterId,
                  workflowModuleId,
                  levelsConfiguring(adapterId, NameClashAvoidance.BY_ADAPTER).isEmpty()
                      ? " (nothing is configured, so the default applies)"
                      : "",
                  adapterId,
                  adapterId,
                  workflowModuleId,
                  adapterId));
    }

    final var levels = levelsConfiguring(adapterId, NameClashAvoidance.BY_ADAPTER);
    // BY_ADAPTER is the DEFAULT, so an adapter of a BPMS without isolation has to
    // report the levels where nothing is configured either - the developer has to
    // choose actively there
    final var unconfiguredLevels = unconfiguredLevels(adapterId);
    if (levels.isEmpty() && unconfiguredLevels.isEmpty()) {
      return;
    }
    final var affected = new LinkedList<String>(levels);
    affected.addAll(unconfiguredLevels);
    throw new IllegalStateException(
        """
            %s has no isolation mechanism of its own, so the name-clash-avoidance mode \
            '%s' cannot be served by adapter '%s'! Affected configuration: %s. Choose \
            explicitly:
              vanillabp.adapters.%s.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
              vanillabp.adapters.%s.name-clash-avoidance: none         # your identifiers are unique already
            The same key may be set per workflow module and workflow \
            (vanillabp.workflow-modules.<module>.adapters.%s.name-clash-avoidance)."""
            .formatted(
                capitalize(bpmsDescription),
                NameClashAvoidance.BY_ADAPTER.name().toLowerCase().replace('_', '-'),
                adapterId,
                String.join(", ", affected),
                adapterId,
                adapterId,
                adapterId));

  }

  @Override
  public void validateNoCollidingProcessIds(
      final String adapterId,
      final Collection<DeployedProcess> deployedProcesses) {

    if (deployedProcesses == null) {
      return;
    }
    final var byScopedId = new LinkedHashMap<String, DeployedProcess>();
    final var collisions = new LinkedHashMap<String, LinkedList<DeployedProcess>>();
    for (final var deployed : deployedProcesses) {
      final var scoped = scopedProcessId(deployed.workflowModuleId(), deployed.bpmnProcessId(), adapterId);
      final var previous = byScopedId.putIfAbsent(scoped, deployed);
      if (previous == null) {
        continue;
      }
      if (previous.equals(deployed)) {
        continue; // the same process reported twice (several files, several adapters)
      }
      collisions
          .computeIfAbsent(scoped, key -> new LinkedList<>(java.util.List.of(previous)))
          .add(deployed);
    }
    if (collisions.isEmpty()) {
      return;
    }
    final var message = new StringBuilder(
        ("Different BPMN processes deployed to adapter '%s' end up under the SAME identifier! "
            + "Rename one of the colliding workflow modules or BPMN processes:")
            .formatted(adapterId));
    collisions.forEach((
        scoped,
        colliding) -> message.append(
            """

                - '%s' is produced by %s"""
                .formatted(
                    scoped,
                    colliding
                        .stream()
                        .map(process -> "BPMN process '%s' of workflow module '%s'"
                            .formatted(process.bpmnProcessId(), process.workflowModuleId()))
                        .collect(Collectors.joining(" and ")))));
    throw new IllegalStateException(message.toString());

  }

  /**
   * The configuration levels which explicitly configure the given mode for the
   * given adapter - used for guiding messages.
   */
  private Collection<String> levelsConfiguring(
      final String adapterId,
      final NameClashAvoidance mode) {

    final var levels = new LinkedList<String>();
    if (properties == null) {
      return levels;
    }
    if (mode == valueOfAdapterLevel(adapterId)) {
      levels.add("vanillabp.adapters.%s".formatted(adapterId));
    }
    properties
        .getWorkflowModules()
        .forEach((
            moduleId,
            module) -> {
          if (mode == valueOf(module.getAdapters(), adapterId)) {
            levels.add("vanillabp.workflow-modules.%s.adapters.%s".formatted(moduleId, adapterId));
          }
          module
              .getWorkflows()
              .forEach((
                  workflowId,
                  workflow) -> {
                if (mode == valueOf(workflow.getAdapters(), adapterId)) {
                  levels.add(
                      "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s"
                          .formatted(moduleId, workflowId, adapterId));
                }
              });
        });
    return levels;

  }

  /**
   * Whether the adapter has NO mode configured at all AND defaults to
   * {@link NameClashAvoidance#BY_ADAPTER} - which an adapter without native isolation
   * cannot serve either. An adapter defaulting to another mode is unaffected.
   */
  private Collection<String> unconfiguredLevels(
      final String adapterId) {

    if ((properties == null) || (valueOfAdapterLevel(adapterId) != null) || (defaultModeFor(
        adapterId) != NameClashAvoidance.BY_ADAPTER)) {
      return java.util.List.of();
    }
    return java.util.List.of("vanillabp.adapters.%s (nothing configured, the default applies)".formatted(adapterId));

  }

  private NameClashAvoidance valueOfAdapterLevel(
      final String adapterId) {

    final var adapter = properties
        .getAdapters()
        .get(adapterId);
    return adapter != null
        ? adapter.getNameClashAvoidance()
        : null;

  }

  private static NameClashAvoidance valueOf(
      final java.util.Map<String, ? extends AdapterProperties> adaptersOfLevel,
      final String adapterId) {

    if (adaptersOfLevel == null) {
      return null;
    }
    final var adapter = adaptersOfLevel.get(adapterId);
    return adapter != null
        ? adapter.getNameClashAvoidance()
        : null;

  }

  private static String join(
      final String... parts) {

    return String.join(SEPARATOR, parts);

  }

  /**
   * Strips the given prefix if - and only if - the identifier carries it.
   */
  private static String stripKnownPrefix(
      final String identifier,
      final String prefix) {

    return identifier.startsWith(prefix)
        ? identifier.substring(prefix.length())
        : identifier;

  }

  private static String capitalize(
      final String text) {

    return (text == null) || text.isEmpty()
        ? String.valueOf(text)
        : Character.toUpperCase(text.charAt(0)) + text.substring(1);

  }

}
