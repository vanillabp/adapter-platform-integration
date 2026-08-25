package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.config.OutfadedVersionsInUsePolicy;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The startup check for old process versions: a BPMS keeps every version of a process
 * it was ever given, and workflows keep running on them, while the application only
 * brings the newest model with it. Whether the application still SERVES the older
 * versions is therefore a question worth asking while it boots - without it the first
 * news of a version nobody serves is an incident on a live workflow.
 * <p>
 * The check runs once per BPMN process after its workflow module was deployed. Reading
 * an old model belongs to the adapter ({@link ProcessVersionCatalogAccess}), deciding
 * whether a method serves it belongs to the core, and the two ends meet here.
 * <p>
 * How loud a finding is depends on whether workflows still run on that version: a
 * version nobody runs is a warning, a version with running workflows is FATAL and,
 * where the operator asked for it, the end of the boot.
 * <p>
 * Why the core drives this check while an adapter only answers two questions is decision 15 in the
 * repository's DECISIONS.md.
 */
public class DeployedProcessVersionsCheck {

  private static final Logger log = LoggerFactory.getLogger(DeployedProcessVersionsCheck.class);

  /**
   * Which of the given tasks no <code>&#64;WorkflowTask</code> method serves in that
   * version - answered by the {@link WorkflowTaskRegistry}, which is the only place
   * knowing the version ranges of the methods.
   */
  @FunctionalInterface
  public interface UnservedTasks {

    Collection<BpmnTaskSpec> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        Collection<BpmnTaskSpec> tasks);

  }

  /**
   * Which methods serve none of the versions worth serving - answered by the
   * {@link WorkflowTaskRegistry} for all three annotations carrying a
   * <code>version</code> attribute.
   */
  @FunctionalInterface
  public interface DeadHandlers {

    List<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        Collection<String> servableVersions,
        VersionRange.ProcessVersionResolver resolver);

  }

  /**
   * What the check reads from an adapter's catalog - narrowed to its two questions so a
   * test double does not have to be a whole catalog.
   */
  public interface ProcessVersionCatalogAccess {

    List<DeployedProcessVersion> deployedVersionsOf(
        String workflowModuleId,
        String bpmnProcessId);

    Collection<BpmnTaskSpec> tasksOfVersion(
        String workflowModuleId,
        String bpmnProcessId,
        String version);

    Long activeInstanceCountOf(
        String workflowModuleId,
        String bpmnProcessId,
        String version);

    default String whatOlderVersionsMiss(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return null;

    }

  }

  private final ProcessVersions processVersions;

  private final OutfadedProcessVersions outfadedVersions;

  private final UnservedTasks unservedTasks;

  private final DeadHandlers deadHandlers;

  /**
   * The adapters already reported as unable to answer, so a BPMS which cannot read old
   * models says so once per process instead of once per version.
   */
  private final Set<String> reportedAsUnableToTell = ConcurrentHashMap.newKeySet();

  public DeployedProcessVersionsCheck(
      final ProcessVersions processVersions,
      final OutfadedProcessVersions outfadedVersions,
      final UnservedTasks unservedTasks,
      final DeadHandlers deadHandlers) {

    this.processVersions = processVersions;
    this.outfadedVersions = outfadedVersions;
    this.unservedTasks = unservedTasks;
    this.deadHandlers = deadHandlers;

  }

  /**
   * Runs the check for one BPMN process, for every BPMS serving it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @throws IllegalStateException If the configuration fades out the version this boot
   *           deployed, or if workflows run on an outfaded version and the policy is
   *           {@link OutfadedVersionsInUsePolicy#FAIL}
   */
  public void check(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    processVersions
        .registeredCatalogs(workflowModuleId, bpmnProcessId)
        .forEach(registered -> check(
            workflowModuleId,
            bpmnProcessId,
            registered.adapterId(),
            access(registered.catalog()),
            resolver));

  }

  /**
   * The check for ONE adapter - the entry point of the tests, which hand in their own
   * {@link ProcessVersionCatalogAccess}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param catalog What that BPMS can tell about the process
   * @param resolver Resolves version tags of that process
   */
  public void check(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final ProcessVersionCatalogAccess catalog,
      final VersionRange.ProcessVersionResolver resolver) {

    final var deployed = processVersions.deployedVersion(adapterId, workflowModuleId, bpmnProcessId);
    if (deployed == null) {
      // a BPMS counting no versions: there is no "older version" to speak of
      return;
    }
    failIfDeployedVersionIsOutfaded(workflowModuleId, bpmnProcessId, adapterId, deployed, resolver);

    final var known = catalog.deployedVersionsOf(workflowModuleId, bpmnProcessId);
    if ((known == null) || known.isEmpty()) {
      return;
    }
    reportDeadHandlers(workflowModuleId, bpmnProcessId, adapterId, known, deployed, resolver);
    reportWorkflowsOnOlderVersions(
        workflowModuleId, bpmnProcessId, adapterId, olderThan(known, deployed), catalog);
    for (final var version : olderThan(known, deployed)) {
      if (outfadedVersions.isOutfaded(workflowModuleId, bpmnProcessId, adapterId, version, resolver)) {
        reportOutfadedVersionInUse(workflowModuleId, bpmnProcessId, adapterId, version, catalog);
        continue;
      }
      final var tasks = catalog.tasksOfVersion(workflowModuleId, bpmnProcessId, version);
      if (tasks == null) {
        reportUnableToReadModels(workflowModuleId, bpmnProcessId, adapterId);
        return;
      }
      final var unserved = unservedTasks.of(workflowModuleId, bpmnProcessId, version, tasks);
      if ((unserved == null) || unserved.isEmpty()) {
        continue;
      }
      reportUnservedTasks(workflowModuleId, bpmnProcessId, adapterId, version, unserved, catalog);
    }

  }

  /**
   * Says how many workflows still run on a version older than the one this boot
   * deployed, and what those workflows will not get.
   *
   * <h2>Why this is worth a line even when everything is served</h2>
   *
   * The rest of this check reports a DEFECT: a task definition nobody serves, a version
   * faded out while workflows are on it. This reports the normal case right after an
   * application was upgraded, where every task IS served and the workflows are simply
   * older than the model. Nothing is wrong, and something is still worth knowing: a
   * feature which an adapter attaches to the MODEL it deploys cannot reach a workflow
   * which was started before, for the rest of that workflow's life. The number falls to
   * zero on its own as those workflows end, which is exactly what makes it useful to an
   * operator on the day of an upgrade.
   *
   * <h2>Why the number is trustworthy</h2>
   *
   * An older version exists only where the deployed model DIFFERS from what was deployed
   * before, and an adapter rewrites a model only to add something. So the same rewrite
   * which produced the new version is what the older workflows lack, and where nothing
   * was rewritten there is no older version and nothing is missing. The two questions
   * have one answer, which is why counting the versions answers both.
   *
   * <p>
   * What the older workflows lack is BPMS-specific, so it is not spelled out here: the
   * adapter reports it through {@link ProcessVersionCatalogAccess}, and an adapter which
   * attaches its behaviour while parsing rather than while deploying answers nothing,
   * because for it nothing is missing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param olderVersions The versions older than the deployed one
   * @param catalog What that BPMS can tell about the process
   */
  private void reportWorkflowsOnOlderVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final List<String> olderVersions,
      final ProcessVersionCatalogAccess catalog) {

    if (olderVersions.isEmpty()) {
      return;
    }
    var total = 0L;
    var counted = false;
    for (final var version : olderVersions) {
      final var running = catalog.activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);
      if (running == null) {
        continue;
      }
      counted = true;
      total += running;
    }
    if (!counted || (total == 0)) {
      // a BPMS which cannot count says so elsewhere already, and a version nobody
      // runs on is not news
      return;
    }
    if (!reportedAsUnableToTell.add(adapterId
        + "|older|"
        + workflowModuleId
        + "|"
        + bpmnProcessId)) {
      return;
    }
    final var missing = catalog.whatOlderVersionsMiss(workflowModuleId, bpmnProcessId);
    log.info(
        """
            {} workflow(s) of BPMN process '{}' (workflow module '{}') still run on {} version(s) \
            older than the one adapter '{}' deployed during this boot: {}. They keep being served - \
            this is not a defect - but whatever this version added TO THE MODEL reaches the version \
            it deployed and no earlier one, so those workflows never get it{}. The number falls to \
            zero as they end, and it is what tells you when the difference is gone.""",
        total,
        bpmnProcessId,
        workflowModuleId,
        olderVersions.size(),
        adapterId,
        String.join(", ", olderVersions),
        (missing == null) || missing.isBlank()
            ? ""
            : ": ".concat(missing));

  }

  /**
   * Reports the methods which serve no version worth serving. "Worth
   * serving" is what the BPMS holds minus what the configuration faded out, so fading
   * out a version also tells the developer which methods just became pointless - the
   * code-side counterpart of <code>outfaded-versions-in-use</code>, which speaks about
   * running workflows only.
   */
  private void reportDeadHandlers(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final List<DeployedProcessVersion> known,
      final String deployed,
      final VersionRange.ProcessVersionResolver resolver) {

    if (deadHandlers == null) {
      return;
    }
    final var heldVersions = known == null
        ? List.<String>of()
        : known
            .stream()
            .map(DeployedProcessVersion::version)
            .filter(java.util.Objects::nonNull)
            .toList();
    // the version this boot deployed is held even where the BPMS did not list it
    final var allHeld = heldVersions.contains(deployed)
        ? heldVersions
        : java.util.stream.Stream.concat(heldVersions.stream(), java.util.stream.Stream.of(deployed)).toList();
    final var servable = allHeld
        .stream()
        .filter(version -> !outfadedVersions.isOutfaded(workflowModuleId, bpmnProcessId, adapterId, version, resolver))
        .toList();
    final var outfaded = allHeld
        .stream()
        .filter(version -> !servable.contains(version))
        .toList();
    deadHandlers
        .of(workflowModuleId, bpmnProcessId, servable, resolver)
        .stream()
        .filter(handler -> reportedAsUnableToTell.add(adapterId
            + "|dead|"
            + handler))
        .forEach(handler -> log.warn(
            """
                The {} of BPMN process '{}' (workflow module '{}') matches no version adapter '{}' \
                holds{} - the method never runs. Widen its version range, remove the method, or deploy \
                a version it serves.""",
            handler,
            bpmnProcessId,
            workflowModuleId,
            adapterId,
            outfaded.isEmpty()
                ? " (held: %s)".formatted(String.join(", ", allHeld))
                : " (held: %s, of which %s %s faded out by '%s')".formatted(
                    String.join(", ", allHeld),
                    String.join(", ", outfaded),
                    outfaded.size() == 1
                        ? "is"
                        : "are",
                    OutfadedProcessVersions.propertyName(adapterId))));

  }

  /**
   * The versions the BPMS holds which are OLDER than the one this boot deployed. The
   * catalog reports them in deployment order, so "older" is "before it in that list";
   * a deployed version the catalog does not know at all (a query which failed, a BPMS
   * which does not list what it just accepted) leaves every other version to be
   * checked, which errs towards checking too much rather than too little.
   */
  private static List<String> olderThan(
      final List<DeployedProcessVersion> known,
      final String deployed) {

    final var identifiers = known
        .stream()
        .map(DeployedProcessVersion::version)
        .filter(java.util.Objects::nonNull)
        .toList();
    final var index = identifiers.indexOf(deployed);
    return index < 0
        ? identifiers.stream().filter(version -> !version.equals(deployed)).toList()
        : identifiers.subList(0, index);

  }

  private void failIfDeployedVersionIsOutfaded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String deployed,
      final VersionRange.ProcessVersionResolver resolver) {

    final var covering = outfadedVersions
        .specificationsFor(workflowModuleId, bpmnProcessId, adapterId)
        .stream()
        .filter(specification -> specification.matches(deployed, resolver))
        .map(VersionRange::toString)
        .toList();
    if (covering.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Version '%s' of BPMN process '%s' (workflow module '%s') is the version adapter '%s' \
            deployed during this boot, but the specification(s) %s of '%s' cover it! Fading out the \
            version an application just deployed would leave the process without a served version. \
            Narrow the specification (e.g. '<%s') or remove it."""
            .formatted(
                deployed,
                bpmnProcessId,
                workflowModuleId,
                adapterId,
                covering.stream().map("'%s'"::formatted).collect(Collectors.joining(", ")),
                OutfadedProcessVersions.propertyName(adapterId),
                deployed));

  }

  private void reportUnservedTasks(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final Collection<BpmnTaskSpec> unserved,
      final ProcessVersionCatalogAccess catalog) {

    final var definitions = unserved
        .stream()
        .map(task -> "'%s'".formatted(task.taskDefinition() == null
            ? task.activityId()
            : task.taskDefinition()))
        .distinct()
        .collect(Collectors.joining(", "));
    final var running = catalog.activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);
    final var remedy = """
        Add a @WorkflowTask method whose version range covers version '%s', or declare that version \
        obsolete by adding e.g. '%s' to '%s'."""
        .formatted(version, version, OutfadedProcessVersions.propertyName(adapterId));

    if ((running != null) && (running > 0)) {
      log.error(
          """
              {} workflow(s) still run on version '{}' of BPMN process '{}' (workflow module '{}', \
              adapter '{}'), whose task definition(s) {} are served by NO @WorkflowTask method of this \
              application - each of them will fail with an incident at its next such task! {}""",
          running,
          version,
          bpmnProcessId,
          workflowModuleId,
          adapterId,
          definitions,
          remedy);
      return;
    }
    log.warn(
        """
            Version '{}' of BPMN process '{}' (workflow module '{}') is still deployed at adapter '{}' \
            and its task definition(s) {} are served by NO @WorkflowTask method of this application{}. \
            {}""",
        version,
        bpmnProcessId,
        workflowModuleId,
        adapterId,
        definitions,
        running == null
            ? ", and this BPMS cannot say whether workflows still run on it"
            : ", no workflow runs on it right now",
        remedy);

  }

  private void reportOutfadedVersionInUse(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final ProcessVersionCatalogAccess catalog) {

    final var running = catalog.activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);
    if (running == null) {
      reportUnableToTellAboutInstances(workflowModuleId, bpmnProcessId, adapterId);
      return;
    }
    if (running == 0) {
      return;
    }
    final var message = """
        %d workflow(s) still run on version '%s' of BPMN process '%s' (workflow module '%s', adapter \
        '%s'), which '%s' fades out - this application does not serve that version any more, so each \
        of them will fail with an incident at its next task whose definition nobody serves! Complete \
        or migrate those workflows, or stop fading out that version. Set \
        'vanillabp.adapters.%s.outfaded-versions-in-use' to 'FAIL' to make this stop the application \
        instead of only reporting it."""
        .formatted(
            running,
            version,
            bpmnProcessId,
            workflowModuleId,
            adapterId,
            OutfadedProcessVersions.propertyName(adapterId),
            adapterId);
    if (outfadedVersions.policyFor(workflowModuleId, bpmnProcessId, adapterId) == OutfadedVersionsInUsePolicy.FAIL) {
      throw new IllegalStateException(message);
    }
    log.error(message);

  }

  private void reportUnableToReadModels(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (!reportedAsUnableToTell.add(adapterId
        + "|models|"
        + workflowModuleId
        + "|"
        + bpmnProcessId)) {
      return;
    }
    log.warn(
        """
            Adapter '{}' cannot read the models of the older versions of BPMN process '{}' (workflow \
            module '{}') its BPMS still holds, so VanillaBP cannot tell whether this application still \
            serves them - the adapter's own log says why. Workflows running on such a version fail with \
            an incident at a task no @WorkflowTask method serves, which is what this check exists to \
            report before it happens.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId);

  }

  private void reportUnableToTellAboutInstances(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (!reportedAsUnableToTell.add(adapterId
        + "|instances|"
        + workflowModuleId
        + "|"
        + bpmnProcessId)) {
      return;
    }
    log.warn(
        """
            Adapter '{}' cannot say how many workflows of BPMN process '{}' (workflow module '{}') still \
            run on the versions '{}' fades out - the adapter's own log says why. The versions stay faded \
            out; workflows still running on one of them fail with an incident at a task no @WorkflowTask \
            method serves.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        OutfadedProcessVersions.propertyName(adapterId));

  }

  private static ProcessVersionCatalogAccess access(
      final io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog catalog) {

    return new ProcessVersionCatalogAccess() {

      @Override
      public List<DeployedProcessVersion> deployedVersionsOf(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return catalog.deployedVersionsOf(workflowModuleId, bpmnProcessId);

      }

      @Override
      public Collection<BpmnTaskSpec> tasksOfVersion(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String version) {

        return catalog.tasksOfVersion(workflowModuleId, bpmnProcessId, version);

      }

      @Override
      public Long activeInstanceCountOf(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String version) {

        return catalog.activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);

      }

      @Override
      public String whatOlderVersionsMiss(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return catalog.whatOlderVersionsMiss(workflowModuleId, bpmnProcessId);

      }

    };

  }

}
