package io.vanillabp.integration.adapter.migration.scoping;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * THE implementation of the name-clash-avoidance model. It resolves the mode and builds
 * the identifiers which a BPMS sees. It does not depend on any BPMS on purpose. An adapter
 * only decides WHERE to apply the results: in its model and in its commands.
 *
 * <h2>Resolution</h2>
 *
 * The mode is an ADAPTER-SCOPED property. So the most specific level wins: workflow before
 * workflow module before adapter ({@link MigrationAdapterProperties#resolveForAdapter}).
 * That allows two adapter ids to have DIFFERENT modes for the same workflow module. The
 * migration path described at {@link NameClashAvoidance} is built on this. Without any
 * configuration the ADAPTER's default applies
 * ({@link AdapterDeploymentService#defaultNameClashAvoidance()}). That is
 * {@link NameClashAvoidance#BY_ADAPTER}, the behavior of VanillaBP 1, for a BPMS which
 * keeps things apart out of the box. It is {@link NameClashAvoidance#NONE} for a BPMS which
 * has to be set up for it first. Camunda 8, for example, rejects tenant ids unless
 * multi-tenancy is enabled. A resolved {@link NameClashAvoidance#NONE} protects nothing. So
 * the adapter reports it with its own WARN, once per workflow module and adapter
 * ({@link AdapterDeploymentService#warnAboutUnscopedIdentifiers(String, boolean)}). Only the
 * adapter knows what its BPMS offers instead.
 *
 * <h2>Building identifiers and taking them apart</h2>
 *
 * Outgoing identifiers are always built from KNOWN parts. Incoming ones are taken apart by
 * matching a KNOWN prefix, never by searching for the first
 * {@link NameClashAvoidanceSupport#SEPARATOR}. So the separator is only chosen for
 * readability. What keeps things correct is {@link #validateNoCollidingProcessIds}.
 *
 * <h2>Two workflow modules under one identifier</h2>
 *
 * Each workflow module is deployed on its own. So {@link #validateNoCollidingProcessIds}
 * gets one module at a time, but the clash it looks for lies between two of them.
 * {@link #moduleDeclaringScopedIdentifier} spans the modules. The sibling reports fill this
 * map already, so the caller's collection may stay as narrow as its deployment. The mode
 * then decides what two equal strings mean. Under {@link NameClashAvoidance#BY_ADAPTER} the
 * BPMS keeps the modules apart. The core cannot see the scopes of the BPMS, so it asks the
 * adapter ({@link AdapterDeploymentService#ownIsolationSeparatesWorkflowModules}).
 *
 * <h2>What the BPMS already held</h2>
 *
 * The checks above compare what this application deploys against itself. They do not see
 * the identifiers which somebody else put into the same BPMS earlier. Asking about those is
 * the adapter's job, because only the adapter can query its BPMS. The answer arrives at
 * {@link #reportIdentifiersTheBpmsAlreadyHolds}. The core builds the scoped form of the
 * adapter's plain identifiers, resolves the mode which produced it, and words the warning.
 * So a developer reads our side, their side and the change which frees the name in one
 * message. It stays a warning, because the holder may be an application which runs
 * correctly.
 * <p>
 * Decision 9 in the repository's DECISIONS.md says why this is the one place which resolves
 * the mode and builds the scoped form.
 */
public class NameClashAvoidanceService implements NameClashAvoidanceSupport {

  private final MigrationAdapterProperties properties;

  /**
   * The adapters' deployment services, resolved LAZILY. Every adapter receives this
   * service, so it has to be built before any adapter exists. The result is cached once it
   * is not empty. An empty result means that the adapter beans were not created yet.
   */
  private final Supplier<Collection<AdapterDeploymentService<?, ?>>> deploymentServices;

  private volatile Map<String, AdapterDeploymentService<?, ?>> deploymentServicesByAdapterId;

  /**
   * The pairs of workflow module and adapter which were already reported as unscoped. The
   * mode is resolved at every runtime boundary, but the WARN belongs to the startup.
   */
  private final Set<String> unscopedReported = ConcurrentHashMap.newKeySet();

  /**
   * Which workflow module declares a scoped identifier, per adapter id and kind. It is
   * filled while the modules are deployed. It is read again when the BPMS still holds a
   * version with the same name. The FIRST module which declared the name keeps the entry. A
   * message needs one other side to name, and a third module which collides is reported
   * against the same one.
   * <p>
   * Three checks read this one map, because all three ask the same question: which workflow
   * module already reaches the BPMS under this form? Each module is deployed on its own. So
   * none of the checks can answer from the one module it was given. The boot spans the
   * modules, and this map lives for the whole boot. That is why
   * {@link #validateNoCollidingProcessIds} keeps its BPMN process ids here, under
   * {@link ScopedIdentifierKind#BPMN_PROCESS_ID}, and not in a memory of its own. The key
   * has the kind in it, so the kinds stay apart.
   */
  private final Map<String, Declaration> moduleDeclaringScopedIdentifier = new ConcurrentHashMap<>();

  /**
   * What the adapter answered about two workflow modules of one adapter id. So a module with
   * two hundred colliding processes asks once and not two hundred times. The answer comes
   * from configuration which does not change while an application boots. That is why it may
   * be kept at all. Decision 19 in the repository's DECISIONS.md limits what a start may
   * ask.
   */
  private final Map<String, Boolean> isolationAnswers = new ConcurrentHashMap<>();

  /**
   * Where a finding goes which the start survives, so that the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * The workflow modules whose CURRENT models an adapter read for
   * {@link #reportIdentifiersTheModelsDeclare}, keyed by adapter id and module.
   * <p>
   * This set tells a name of a held version which is really missed from one which is just
   * absent. If nobody ever said what this deployment declares, every old name looks new. Then
   * the check about names which nobody sends any more would report a whole model. So an
   * adapter which does not read identifiers at all silences that check instead of feeding it
   * (decision 38 in the repository's DECISIONS.md).
   */
  private final Set<String> modulesWhoseIdentifiersWereRead = ConcurrentHashMap.newKeySet();

  /**
   * Where a scoped identifier was read: the workflow module it belongs to, and the BPMN
   * process for a kind which is scoped per process. For a BPMN process id the process is the
   * identifier itself. That lets the collision check tell a process reported twice from two
   * processes which share one form.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process, or <code>null</code> for a module-wide name
   */
  private record Declaration(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  /**
   * Without the adapters' deployment services, the default of every adapter is
   * {@link NameClashAvoidance#BY_ADAPTER}, and no adapter can report an unscoped workflow
   * module. This is for tests and for platforms which do not pass them.
   *
   * @param properties The VanillaBP configuration
   */
  public NameClashAvoidanceService(
      final MigrationAdapterProperties properties) {

    this(properties, List::of);

  }

  /**
   * The full service, which is what a platform integration builds.
   * <p>
   * An adapter's own default applies only where nothing is configured. The configured value
   * always wins. That is why the adapters are asked as late as possible.
   *
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
    this.findings = properties == null
        // a service built without a configuration is a test fixture. It has no start to
        // close either
        ? new io.vanillabp.integration.adapter.migration.startup.StartupFindings()
        : properties.startupFindings();

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
   * The mode for the given adapter when nothing is configured. That is the adapter's own
   * default, or {@link NameClashAvoidance#BY_ADAPTER} for an adapter which is unknown here
   * (see {@link #deploymentServices}).
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
   * Lets the adapter report the workflow module as unscoped, once per workflow module and
   * adapter id. Resolving a mode without a workflow module reports nothing, for example while
   * two adapter instances are compared. The resolutions per module during the deployment do
   * the reporting.
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
            // an empty result means the adapters were not created yet, so ask again
            deploymentServicesByAdapterId = collected;
          }
        }
      }
    }
    return byAdapterId.get(adapterId);

  }

  /**
   * Whether task definitions are also scoped by their BPMN process ID (default
   * <code>true</code>, see {@link AdapterProperties#getPrefixTaskDefinitionsPerProcess()}).
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
    return (configured == null) || configured;

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
  public void validateNoneNameClashStrategy(
      final String adapterId,
      final String byAdapterOnlyPropertyKey) {

    if ((byAdapterOnlyPropertyKey == null) || byAdapterOnlyPropertyKey.isBlank()) {
      return;
    }
    if (appliesByAdapterAnywhere(adapterId)) {
      return;
    }
    throw new IllegalStateException(
        """
            The property '%s' is configured, but nothing can use it: it takes effect only \
            where the name-clash-avoidance mode is 'by-adapter' - the BPMS' own isolation - \
            and the mode applying to adapter '%s' is %s. The two contradict each other, so \
            choose the one you mean:
              - remove '%s' if the workflow modules are not meant to be kept apart by the BPMS itself, or
              - vanillabp.adapters.%s.name-clash-avoidance: by-adapter   # let the BPMS keep them apart
            The mode may also be set per workflow module \
            (vanillabp.workflow-modules.<module>.adapters.%s.name-clash-avoidance), which is \
            enough to put the property to use for that module."""
            .formatted(
                byAdapterOnlyPropertyKey,
                adapterId,
                modesApplying(adapterId),
                byAdapterOnlyPropertyKey,
                adapterId,
                adapterId));

  }

  /**
   * Whether {@link NameClashAvoidance#BY_ADAPTER} applies anywhere for the given adapter. In
   * other words, whether the BPMS itself keeps any workflow module apart. That is so where a
   * level configures it, or where it is the adapter's default and at least one level leaves
   * the mode unconfigured.
   */
  private boolean appliesByAdapterAnywhere(
      final String adapterId) {

    if (properties == null) {
      return true; // nothing is known about the configuration, so nothing is claimed
    }
    if (!levelsConfiguring(adapterId, NameClashAvoidance.BY_ADAPTER).isEmpty()) {
      return true;
    }
    if (valueOfAdapterLevel(adapterId) != null) {
      return false; // configured, and it is not BY_ADAPTER (that was checked above)
    }
    if (defaultModeFor(adapterId) != NameClashAvoidance.BY_ADAPTER) {
      return false;
    }
    // the adapter's default applies wherever no level overrides it
    final var modules = properties.getWorkflowModules();
    return modules.isEmpty() || modules
        .values()
        .stream()
        .anyMatch(module -> valueOf(module.getAdapters(), adapterId) == null);

  }

  /**
   * The modes which apply to the given adapter, for messages: the values configured at any
   * level, plus the adapter's default where nothing is configured.
   */
  private String modesApplying(
      final String adapterId) {

    final var modes = new java.util.TreeSet<String>();
    for (final var mode : NameClashAvoidance.values()) {
      if (!levelsConfiguring(adapterId, mode).isEmpty()) {
        modes.add(nameOf(mode));
      }
    }
    if ((properties == null) || (valueOfAdapterLevel(adapterId) == null)) {
      modes.add(nameOf(defaultModeFor(adapterId)));
    }
    return modes
        .stream()
        .collect(Collectors.joining("' and '", "'", "'"));

  }

  private static String nameOf(
      final NameClashAvoidance mode) {

    return mode
        .name()
        .toLowerCase()
        .replace('_', '-');

  }

  @Override
  public void validateNativeIsolationSupported(
      final String adapterId,
      final String workflowModuleId,
      final String bpmsDescription) {

    if (workflowModuleId != null) {
      // the mode is resolved per workflow module, so check the module being deployed
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
    // BY_ADAPTER is the DEFAULT. So an adapter of a BPMS without isolation also has to
    // report the levels where nothing is configured. The developer has to choose there
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
    final var collisions = new LinkedList<String>();
    final var fixes = new LinkedHashSet<String>();
    for (final var deployed : deployedProcesses) {
      if ((deployed == null) || (deployed.workflowModuleId() == null) || (deployed.bpmnProcessId() == null)) {
        continue;
      }
      final var scopedForm = scopedProcessId(deployed.workflowModuleId(), deployed.bpmnProcessId(), adapterId);
      final var beingDeployed = new Declaration(deployed.workflowModuleId(), deployed.bpmnProcessId());
      final var alreadyThere = moduleDeclaringScopedIdentifier
          .putIfAbsent(
              declarationKey(adapterId, ScopedIdentifierKind.BPMN_PROCESS_ID, scopedForm),
              beingDeployed);
      if ((alreadyThere == null) || alreadyThere.equals(beingDeployed)) {
        // the first process which reaches the BPMS under that form, or the same process
        // handed over twice. One BPMN file with several processes does that, and so does a
        // module deployed to several adapters
        continue;
      }
      if (somethingSeparates(adapterId, alreadyThere, beingDeployed)) {
        continue;
      }
      fixes.add(howToSeparateProcesses(adapterId, alreadyThere, beingDeployed));
      collisions.add(bothSidesOfTheClash(adapterId, alreadyThere, beingDeployed, scopedForm));
    }
    if (collisions.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Two BPMN processes deployed to adapter '%s' reach the BPMS under the SAME identifier! \
            The BPMS keeps one definition under that identifier and loses the other, so one of the \
            two workflow modules would run on a model nobody deployed.
            What collides:%s
            What to change:%s"""
            .formatted(adapterId, asBullets(collisions), asBullets(fixes)));

  }

  /**
   * Both sides of one collision, each with the mode and the property key which produced its
   * form. Two plain ids meet because of a mixed configuration. A reader who gets one mode for
   * both sides goes looking for a line which does not decide anything.
   */
  private String bothSidesOfTheClash(
      final String adapterId,
      final Declaration oneSide,
      final Declaration otherSide,
      final String scopedForm) {

    return """
        BPMN process '%s' of workflow module '%s' (%s) and BPMN process '%s' of workflow module \
        '%s' (%s) both reach the BPMS as '%s'"""
        .formatted(
            oneSide.bpmnProcessId(),
            oneSide.workflowModuleId(),
            modeClauseOf(adapterId, oneSide),
            otherSide.bpmnProcessId(),
            otherSide.workflowModuleId(),
            modeClauseOf(adapterId, otherSide),
            scopedForm);

  }

  private String modeClauseOf(
      final String adapterId,
      final Declaration side) {

    return "mode '%s', %s"
        .formatted(
            nameOf(modeFor(side.workflowModuleId(), side.bpmnProcessId(), adapterId)),
            whereTheModeComesFrom(side.workflowModuleId(), side.bpmnProcessId(), adapterId));

  }

  /**
   * Whether anything keeps apart two BPMN processes which reach the BPMS under one
   * identifier. This answer decides whether the check repairs a blind spot or refuses correct
   * applications. Under {@link NameClashAvoidance#BY_ADAPTER} the scoped form is the plain
   * one. So two workflow modules which a tenant keeps apart perfectly well arrive here with
   * two equal strings.
   */
  private boolean somethingSeparates(
      final String adapterId,
      final Declaration oneSide,
      final Declaration otherSide) {

    if (oneSide.workflowModuleId().equals(otherSide.workflowModuleId())) {
      // a BPMS keeps workflow modules apart. Inside one module nothing is left which could
      // tell two processes apart
      return false;
    }
    final var oneMode = modeFor(oneSide.workflowModuleId(), oneSide.bpmnProcessId(), adapterId);
    final var otherMode = modeFor(otherSide.workflowModuleId(), otherSide.bpmnProcessId(), adapterId);
    if ((oneMode != NameClashAvoidance.BY_ADAPTER) && (otherMode != NameClashAvoidance.BY_ADAPTER)) {
      // 'use-prefix' means this service built both strings itself, 'none' means it built
      // neither. Either way the two equal strings are the whole answer. A mix of the two
      // modes is how the prefixed form of one module meets the plain id of another
      return false;
    }
    return ownIsolationSeparates(adapterId, oneSide.workflowModuleId(), otherSide.workflowModuleId());

  }

  /**
   * What the adapter says about the two workflow modules, asked once per pair. An adapter
   * which this service does not know answers like one without isolation. That is the side
   * which refuses. A platform which passes no deployment services is a test. A test which
   * wants the modules kept apart says so by answering.
   */
  private boolean ownIsolationSeparates(
      final String adapterId,
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    // the two ids sorted, so the answer is the same whichever module deploys first, and the
    // adapter is asked once and not once per order
    final var first = oneWorkflowModuleId.compareTo(anotherWorkflowModuleId) <= 0
        ? oneWorkflowModuleId
        : anotherWorkflowModuleId;
    final var second = first.equals(oneWorkflowModuleId)
        ? anotherWorkflowModuleId
        : oneWorkflowModuleId;
    return isolationAnswers
        .computeIfAbsent(
            "%s|%s|%s".formatted(adapterId, first, second),
            key -> {
              final var deploymentService = deploymentServiceOf(adapterId);
              return (deploymentService != null) && deploymentService.ownIsolationSeparatesWorkflowModules(first,
                  second);
            });

  }

  /**
   * What frees the identifier. That differs per mode, and again where both processes belong
   * to one workflow module. A message which names a property that changes nothing for this
   * case sends the developer to the wrong line.
   */
  private String howToSeparateProcesses(
      final String adapterId,
      final Declaration oneSide,
      final Declaration otherSide) {

    if (oneSide.workflowModuleId().equals(otherSide.workflowModuleId())) {
      return """
          inside workflow module '%s' only a rename helps, because both processes are scoped \
          by that module: rename BPMN process '%s' or '%s'"""
          .formatted(oneSide.workflowModuleId(), oneSide.bpmnProcessId(), otherSide.bpmnProcessId());
    }
    final var modes = new LinkedHashSet<NameClashAvoidance>();
    modes.add(modeFor(oneSide.workflowModuleId(), oneSide.bpmnProcessId(), adapterId));
    modes.add(modeFor(otherSide.workflowModuleId(), otherSide.bpmnProcessId(), adapterId));
    return modes
        .stream()
        .map(mode -> howToSeparateModules(mode, adapterId, oneSide.workflowModuleId(), otherSide.workflowModuleId()))
        .collect(Collectors.joining("; or "));

  }

  private static String howToSeparateModules(
      final NameClashAvoidance mode,
      final String adapterId,
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    return switch (mode) {
      case USE_PREFIX -> """
          where the mode is 'use-prefix': the prefix carries the workflow module id already, so \
          it is the composition which is ambiguous. Rename workflow module '%s' or '%s', or \
          rename one of the two BPMN processes"""
          .formatted(oneWorkflowModuleId, anotherWorkflowModuleId);
      case NONE -> """
          where the mode is 'none': nothing is scoped, so two workflow modules using one BPMN \
          process id share the definition. Put the module id in front of it with \
          'vanillabp.adapters.%s.name-clash-avoidance: use-prefix', let the BPMS keep the \
          modules apart with 'vanillabp.adapters.%s.name-clash-avoidance: by-adapter', or rename \
          one of the two BPMN processes"""
          .formatted(adapterId, adapterId);
      case BY_ADAPTER -> """
          where the mode is 'by-adapter': adapter '%s' says its BPMS would deploy workflow \
          module '%s' and workflow module '%s' into ONE scope, so its isolation separates \
          nothing here. Give the two modules scopes of their own - where the adapter uses a \
          tenant for it, that is 'vanillabp.adapters.%s.tenant-id', which may be set per \
          workflow module (vanillabp.workflow-modules.<module>.adapters.%s.tenant-id) - or \
          switch to 'vanillabp.adapters.%s.name-clash-avoidance: use-prefix'"""
          .formatted(
              adapterId,
              oneWorkflowModuleId,
              anotherWorkflowModuleId,
              adapterId,
              adapterId,
              adapterId);
    };

  }

  @Override
  public void reportIdentifiersTheBpmsAlreadyHolds(
      final String adapterId,
      final String workflowModuleId,
      final Collection<IdentifierHeldElsewhere> found) {

    if (found == null) {
      return;
    }
    final var holdings = found
        .stream()
        .filter(held -> (held != null) && (held.kind() != null) && (held.plainIdentifier() != null))
        .toList();
    if (holdings.isEmpty()) {
      return;
    }

    // the fix depends on the mode, and the mode is resolved per workflow and per workflow
    // module. So ten findings may need two sentences or one. The fixes are collected while
    // the findings are worded, and each is written once
    final var fixes = new LinkedHashSet<String>();
    final var describedHoldings = new LinkedList<String>();
    for (final var held : holdings) {
      final var processDecidingTheMode = processDecidingTheMode(held);
      final var mode = modeFor(workflowModuleId, processDecidingTheMode, adapterId);
      fixes.add(howToFree(mode, adapterId, workflowModuleId));
      describedHoldings.add(describe(held, workflowModuleId, adapterId, mode));
    }

    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "workflow module '%s', adapter '%s'".formatted(workflowModuleId, adapterId),
            """
                The adapter asked its BPMS which identifiers of this workflow module it already \
                holds, and got %d of them back. Both sides deploy the same name, so the BPMS \
                alone decides which of them a start or a message reaches. This does not stop the \
                boot: whoever holds the name may be an application which runs correctly, and \
                ending this boot would not help it.
                What this application deploys, and who holds it already:%s
                What to change:%s"""
                .formatted(
                    holdings.size(),
                    asBullets(describedHoldings),
                    asBullets(fixes)));

  }

  /**
   * One block of the warning, so that ten findings stay readable: one line each, below the
   * sentence which introduces them.
   */
  private static String asBullets(
      final Collection<String> lines) {

    return lines
        .stream()
        .collect(Collectors.joining("\n  - ", "\n  - ", ""));

  }

  /**
   * The BPMN process whose mode decides about the given finding. That is the process id
   * itself where the finding IS a process id, the process of a task definition, and none
   * for an identifier which only the workflow module scopes.
   */
  private static String processDecidingTheMode(
      final IdentifierHeldElsewhere held) {

    return switch (held.kind()) {
      case BPMN_PROCESS_ID -> held.plainIdentifier();
      case TASK_DEFINITION -> held.bpmnProcessId();
      case MESSAGE_NAME, SIGNAL_NAME, ERROR_CODE, ESCALATION_CODE, DMN_DECISION_ID -> null;
    };

  }

  /**
   * One finding as the warning names it: what the application calls the identifier, what
   * the BPMS sees instead, where that form comes from, and what the adapter found out about
   * the holder.
   */
  private String describe(
      final IdentifierHeldElsewhere held,
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidance mode) {

    final var scopedForm = scopedFormOf(
        held.kind(),
        workflowModuleId,
        held.bpmnProcessId(),
        held.plainIdentifier(),
        adapterId);
    return """
        %s '%s'%s, which the BPMS %s (mode '%s', %s), is already held by %s%s"""
        .formatted(
            kindOf(held.kind()),
            held.plainIdentifier(),
            (held.kind() == ScopedIdentifierKind.TASK_DEFINITION) && (held.bpmnProcessId() != null)
                ? " of BPMN process '%s'".formatted(held.bpmnProcessId())
                : "",
            held.plainIdentifier().equals(scopedForm)
                ? "sees unchanged"
                : "sees as '%s'".formatted(scopedForm),
            nameOf(mode),
            whereTheModeComesFrom(workflowModuleId, processDecidingTheMode(held), adapterId),
            (held.heldBy() == null) || held.heldBy().isBlank()
                ? "a deployment this adapter cannot describe any further"
                : held.heldBy(),
            held.certainlyForeign()
                ? ""
                : ". VanillaBP cannot tell this from an earlier deployment of this application, so this line may be harmless");

  }

  /**
   * The scoped form which the BPMS sees for one identifier of one workflow module. It is
   * built here, because every caller hands over only the plain identifier.
   */
  private String scopedFormOf(
      final ScopedIdentifierKind kind,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String plainIdentifier,
      final String adapterId) {

    return switch (kind) {
      case BPMN_PROCESS_ID -> scopedProcessId(workflowModuleId, plainIdentifier, adapterId);
      // a task definition belongs to its BPMN process. A caller who knows no process gets
      // the module-wide form. An application which switches the process part off deploys
      // that form as well
      case TASK_DEFINITION -> bpmnProcessId == null
          ? scopedIdentifier(workflowModuleId, plainIdentifier, adapterId)
          : scopedTaskDefinition(workflowModuleId, bpmnProcessId, plainIdentifier, adapterId);
      // several processes of a module call a decision. So it is scoped by the module
      // alone, exactly like a message or an error code
      case MESSAGE_NAME, SIGNAL_NAME, ERROR_CODE, ESCALATION_CODE, DMN_DECISION_ID -> scopedIdentifier(
          workflowModuleId,
          plainIdentifier,
          adapterId);
    };

  }

  /**
   * How the warning names a kind of identifier: in the words a developer sees in a modeller,
   * not in the words of the enum.
   */
  private static String kindOf(
      final ScopedIdentifierKind kind) {

    return switch (kind) {
      case BPMN_PROCESS_ID -> "BPMN process id";
      case MESSAGE_NAME -> "message name";
      case SIGNAL_NAME -> "signal name";
      case ERROR_CODE -> "BPMN error code";
      case ESCALATION_CODE -> "escalation code";
      case TASK_DEFINITION -> "task definition";
      case DMN_DECISION_ID -> "DMN decision id";
    };

  }

  /**
   * The property key which produced the mode. So the developer edits the line which decides,
   * and does not add a second one somewhere else.
   */
  private String whereTheModeComesFrom(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var module = properties == null
        ? null
        : properties
            .getWorkflowModules()
            .get(workflowModuleId);
    if (module != null) {
      final var workflow = bpmnProcessId == null
          ? null
          : module
              .getWorkflows()
              .get(bpmnProcessId);
      if ((workflow != null) && (valueOf(workflow.getAdapters(), adapterId) != null)) {
        return "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.name-clash-avoidance"
            .formatted(workflowModuleId, bpmnProcessId, adapterId);
      }
      if (valueOf(module.getAdapters(), adapterId) != null) {
        return "vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance".formatted(workflowModuleId, adapterId);
      }
    }
    if ((properties != null) && (valueOfAdapterLevel(adapterId) != null)) {
      return "vanillabp.adapters.%s.name-clash-avoidance".formatted(adapterId);
    }
    return "nothing is configured, so the default of adapter '%s' applies".formatted(adapterId);

  }

  /**
   * What frees the identifier again, per mode. Each mode has a different way out. Naming the
   * wrong one sends the developer to a property which changes nothing.
   */
  private static String howToFree(
      final NameClashAvoidance mode,
      final String adapterId,
      final String workflowModuleId) {

    return switch (mode) {
      case USE_PREFIX -> """
          where the mode is 'use-prefix': the prefix carries the workflow module id already, so the \
          other side uses the same module id. Rename workflow module '%s', or rename the identifier \
          which clashes"""
          .formatted(workflowModuleId);
      case NONE -> """
          where the mode is 'none': nothing is scoped, so every application on this BPMS sees your \
          plain identifiers. Put the workflow module id in front of them with \
          'vanillabp.adapters.%s.name-clash-avoidance: use-prefix', or let the BPMS keep the modules \
          apart with 'vanillabp.adapters.%s.name-clash-avoidance: by-adapter'"""
          .formatted(adapterId, adapterId);
      case BY_ADAPTER -> """
          where the mode is 'by-adapter': the BPMS is supposed to keep the modules apart, so both \
          applications deploy into the same scope of it. Compare the scope of the two - where the \
          adapter uses a tenant for it, that is 'vanillabp.adapters.%s.tenant-id', and two \
          applications which name the same tenant are not kept apart by it"""
          .formatted(adapterId);
    };

  }

  @Override
  public void reportIdentifiersTheModelsDeclare(
      final String adapterId,
      final String workflowModuleId,
      final Collection<ModelIdentifier> declared) {

    if ((declared == null) || (workflowModuleId == null)) {
      return;
    }
    modulesWhoseIdentifiersWereRead.add("%s|%s".formatted(adapterId, workflowModuleId));
    final var collisions = new LinkedList<String>();
    final var fixes = new LinkedHashSet<String>();
    final var modes = new LinkedHashSet<NameClashAvoidance>();
    for (final var identifier : declared) {
      if ((identifier == null) || (identifier.kind() == null) || (identifier.plainIdentifier() == null)) {
        continue;
      }
      final var scopedForm = scopedFormOf(
          identifier.kind(),
          workflowModuleId,
          identifier.bpmnProcessId(),
          identifier.plainIdentifier(),
          adapterId);
      final var declaredAlready = moduleDeclaringScopedIdentifier
          .putIfAbsent(
              declarationKey(adapterId, identifier.kind(), scopedForm),
              new Declaration(workflowModuleId, identifier.bpmnProcessId()));
      if ((declaredAlready == null) || declaredAlready.workflowModuleId().equals(workflowModuleId)) {
        // the first module which declares it, or this module which declares it in a second
        // process. The scope of such a name IS the workflow module, so sharing it inside one
        // module is normal VanillaBP
        continue;
      }
      final var mode = modeFor(workflowModuleId, null, adapterId);
      modes.add(mode);
      fixes.add(howToFree(mode, adapterId, workflowModuleId));
      collisions
          .add(
              """
                  %s '%s' of workflow module '%s'%s and of workflow module '%s'%s both reach the BPMS \
                  as '%s' (mode '%s', %s)"""
                  .formatted(
                      kindOf(identifier.kind()),
                      identifier.plainIdentifier(),
                      declaredAlready.workflowModuleId(),
                      processClauseOf(identifier.kind(), declaredAlready.bpmnProcessId()),
                      workflowModuleId,
                      processClauseOf(identifier.kind(), identifier.bpmnProcessId()),
                      scopedForm,
                      nameOf(mode),
                      whereTheModeComesFrom(workflowModuleId, null, adapterId)));
    }
    if (collisions.isEmpty()) {
      return;
    }

    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.BPMN_MODELS,
            "adapter '%s'".formatted(adapterId),
            """
                Two workflow modules of this application declare identifiers which this adapter \
                cannot keep apart, so the BPMS sees one name where the application means two. A \
                message correlated for one module can reach a workflow of the other, and a signal \
                broadcast reaches both. The deployment is not stopped for it, because both models \
                stay as they are and two modules may share a name on purpose.%s
                What collides:%s
                What to change:%s"""
                .formatted(
                    whatTheBpmsOwnIsolationHides(modes),
                    asBullets(collisions),
                    asBullets(fixes)));

  }

  @Override
  public void reportIdentifiersOfHeldVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final Long activeWorkflows,
      final Collection<ModelIdentifier> declared) {

    if ((declared == null) || (workflowModuleId == null)) {
      return;
    }
    final var shared = new LinkedList<String>();
    final var fixes = new LinkedHashSet<String>();
    final var modes = new LinkedHashSet<NameClashAvoidance>();
    final var waitedFor = new LinkedHashSet<String>();
    for (final var identifier : declared) {
      if ((identifier == null) || (identifier.kind() == null) || (identifier.plainIdentifier() == null)) {
        continue;
      }
      final var scopedForm = scopedFormOf(
          identifier.kind(),
          workflowModuleId,
          identifier.bpmnProcessId() != null
              ? identifier.bpmnProcessId()
              : bpmnProcessId,
          identifier.plainIdentifier(),
          adapterId);
      final var deployedNow = moduleDeclaringScopedIdentifier
          .get(declarationKey(adapterId, identifier.kind(), scopedForm));
      if (deployedNow == null) {
        if (nobodySendsItAnyMore(adapterId, workflowModuleId, identifier, activeWorkflows)) {
          waitedFor.add("%s '%s'".formatted(kindOf(identifier.kind()), identifier.plainIdentifier()));
        }
        continue;
      }
      if (deployedNow.workflowModuleId().equals(workflowModuleId)) {
        // the module which declares it today is the one the held version belongs to. That is
        // the same model carried forward, not a clash
        continue;
      }
      final var mode = modeFor(deployedNow.workflowModuleId(), null, adapterId);
      modes.add(mode);
      fixes.add(howToFree(mode, adapterId, deployedNow.workflowModuleId()));
      shared
          .add(
              """
                  %s '%s'%s, which the BPMS sees as '%s' (mode '%s', %s), is declared by workflow \
                  module '%s'%s of this deployment as well"""
                  .formatted(
                      kindOf(identifier.kind()),
                      identifier.plainIdentifier(),
                      processClauseOf(identifier.kind(), identifier.bpmnProcessId()),
                      scopedForm,
                      nameOf(mode),
                      whereTheModeComesFrom(deployedNow.workflowModuleId(), null, adapterId),
                      deployedNow.workflowModuleId(),
                      processClauseOf(identifier.kind(), deployedNow.bpmnProcessId())));
    }
    reportNamesNobodySendsAnyMore(adapterId, workflowModuleId, bpmnProcessId, version, activeWorkflows, waitedFor);
    if (shared.isEmpty()) {
      return;
    }

    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "version %s of process '%s' of workflow module '%s', adapter '%s'".formatted(
                version,
                bpmnProcessId,
                workflowModuleId,
                adapterId),
            """
                This version, which the adapter still holds%s, declares identifiers another \
                workflow module of this deployment uses. The BPMS cannot tell the two apart, so a \
                message or a signal meant for one of them can reach the other. Nobody can change \
                the held model any more, so the name has to move on the side being deployed - or \
                the modules have to be scoped apart.%s
                What the held version shares:%s
                What to change:%s"""
                .formatted(
                    workflowsRunningOn(activeWorkflows),
                    whatTheBpmsOwnIsolationHides(modes),
                    asBullets(shared),
                    asBullets(fixes)));

  }

  /**
   * Whether a name which a held version declares is one which nothing sends any more: a
   * message or a signal which reaches a workflow from outside, and which no model of this
   * deployment declares.
   * <p>
   * Only those two kinds count. The model itself throws an error code or an escalation code.
   * So a held version which has one also has whatever throws it. A task definition which
   * nobody serves has a check of its own, which says more than this one could. A BPMN
   * process id of a held version is the process being asked about.
   * <p>
   * A version which no workflow is on is not asked about either. Nothing waits there, so
   * there is nothing to look at. A BPMS which cannot count is asked all the same, because
   * "cannot tell" is not "nobody".
   *
   * @param adapterId The adapter ID whose BPMS holds the version
   * @param workflowModuleId The workflow module of the held version
   * @param identifier One identifier that version declares
   * @param activeWorkflows How many workflows still run on it, or <code>null</code>
   * @return Whether it is worth a look
   */
  private boolean nobodySendsItAnyMore(
      final String adapterId,
      final String workflowModuleId,
      final ModelIdentifier identifier,
      final Long activeWorkflows) {

    if ((identifier.kind() != ScopedIdentifierKind.MESSAGE_NAME) && (identifier
        .kind() != ScopedIdentifierKind.SIGNAL_NAME)) {
      return false;
    }
    if ((activeWorkflows != null) && (activeWorkflows == 0L)) {
      return false;
    }
    // where nobody said what this deployment declares, every old name looks new
    return modulesWhoseIdentifiersWereRead.contains("%s|%s".formatted(adapterId, workflowModuleId));

  }

  /**
   * Says that a held version waits for a name which the current deployment does not declare
   * any more. Renaming a message leaves this behind.
   * <p>
   * The workflows on that version wait for something which nobody sends. Nothing else in
   * VanillaBP would ever say so. The name lives in a model which the BPMS holds, and in no
   * file of the application.
   * <p>
   * The advice is "look at it" and not "write this". The platform can see the case, but it
   * cannot know whether it is intended. An application may have renamed the message on
   * purpose and finished the old workflows by hand. Or nobody may wait at the old version. A
   * refusal here would be a blind one (decision 38 in the repository's DECISIONS.md). So it
   * is a warning. It goes into the block which a start writes at its end, not into a
   * paragraph of its own.
   *
   * @param adapterId The adapter ID whose BPMS holds the version
   * @param workflowModuleId The workflow module of the held version
   * @param bpmnProcessId The plain BPMN process ID of the held version
   * @param version The version identifier the BPMS reported
   * @param activeWorkflows How many workflows still run on it, or <code>null</code>
   * @param waitedFor The names, already worded, empty where there is nothing to say
   */
  private void reportNamesNobodySendsAnyMore(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final Long activeWorkflows,
      final Collection<String> waitedFor) {

    if (waitedFor.isEmpty()) {
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s', adapter '%s'"
                .formatted(bpmnProcessId, workflowModuleId, adapterId),
            """
                Version %s, which this BPMS still holds%s, declares names no model of this \
                deployment declares any more:%s
                A workflow waiting at one of them waits for something nothing sends. That may be \
                what you meant - a name which was renamed and old workflows which were finished by \
                hand - and it may be a rename nobody finished. Look at the workflows still running \
                on that version, and where they do wait, correlate the message under its old name \
                or bring those workflows to an end.\
                """
                .formatted(version, workflowsRunningOn(activeWorkflows), asBullets(waitedFor)));

  }

  /**
   * How urgent the finding about a held version is, in the words the count allows. Somebody
   * has to act on a version which has workflows. A BPMS which cannot count them says that
   * instead of a number.
   */
  private static String workflowsRunningOn(
      final Long activeWorkflows) {

    if (activeWorkflows == null) {
      return " and which this BPMS cannot count the workflows of";
    }
    if (activeWorkflows == 0L) {
      return " with no workflow left on it";
    }
    return activeWorkflows == 1L
        ? " with one workflow still running on it"
        : " with %d workflows still running on it".formatted(activeWorkflows);

  }

  /**
   * What a finding cannot claim where the BPMS itself should keep the workflow modules
   * apart: whether it really does. The core resolves the mode and builds the prefixes. But
   * the adapter knows the isolation of its BPMS, for example a tenant, a namespace or a
   * database of its own. So under that mode the line is a question and not a verdict. A
   * reader who cannot see which of the two they got learns to ignore the whole message.
   */
  private static String whatTheBpmsOwnIsolationHides(
      final Collection<NameClashAvoidance> modes) {

    return modes.contains(NameClashAvoidance.BY_ADAPTER)
        ? """
             Under 'by-adapter' it is the BPMS which is supposed to keep the two apart, and \
            VanillaBP cannot see whether it does: one scope configured for the whole adapter - a \
            tenant id, on the Camunda adapters - puts both workflow modules into it, while a scope \
            per workflow module keeps them apart and makes this harmless."""
        : "";

  }

  /**
   * Which BPMN process a finding names, where the kind is scoped by one. A task definition
   * belongs to its process, and a developer who looks for it needs to know which.
   */
  private static String processClauseOf(
      final ScopedIdentifierKind kind,
      final String bpmnProcessId) {

    return (kind == ScopedIdentifierKind.TASK_DEFINITION) && (bpmnProcessId != null)
        ? " (BPMN process '%s')".formatted(bpmnProcessId)
        : "";

  }

  /**
   * The key under which the module that declares an identifier is remembered. The key has
   * the kind in it, because a BPMS keeps the kinds apart. A message name which equals an
   * error code collides with nothing.
   */
  private static String declarationKey(
      final String adapterId,
      final ScopedIdentifierKind kind,
      final String scopedIdentifier) {

    return "%s|%s|%s".formatted(adapterId, kind, scopedIdentifier);

  }

  /**
   * The configuration levels which explicitly configure the given mode for the given
   * adapter. Used for guiding messages.
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
   * {@link NameClashAvoidance#BY_ADAPTER}. An adapter without its own isolation cannot serve
   * that default either. An adapter with another default is not affected.
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
   * Removes the given prefix, but only if the identifier has it.
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
