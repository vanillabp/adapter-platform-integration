package io.vanillabp.integration.adapter.migration.scoping;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
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
 * the migration path documented on {@link NameClashAvoidance}. The default is
 * {@link NameClashAvoidance#BY_ADAPTER}, VanillaBP 1's behavior.
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

  public NameClashAvoidanceService(
      final MigrationAdapterProperties properties) {

    this.properties = properties;

  }

  @Override
  public NameClashAvoidance modeFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (properties == null) {
      return NameClashAvoidance.BY_ADAPTER;
    }
    final var configured = properties.resolveForAdapter(
        workflowModuleId,
        bpmnProcessId,
        null,
        adapterId,
        AdapterProperties::getNameClashAvoidance);
    return configured != null
        ? configured
        : NameClashAvoidance.BY_ADAPTER;

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
   * Whether the adapter has NO mode configured at all - the default
   * ({@link NameClashAvoidance#BY_ADAPTER}) then applies, which an adapter without
   * native isolation cannot serve either.
   */
  private Collection<String> unconfiguredLevels(
      final String adapterId) {

    if ((properties == null) || (valueOfAdapterLevel(adapterId) != null)) {
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
