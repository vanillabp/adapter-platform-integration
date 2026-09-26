package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.OutfadedVersionsInUsePolicy;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The startup check for old process versions: a BPMS keeps every version of a process
 * it was ever given, and workflows keep running on them, while the application only
 * brings the newest model with it. Whether the application still SERVES the older
 * versions is therefore a question worth asking while it boots - without it the first
 * news of a version nobody serves is an incident on a live workflow.
 * <p>
 * The check runs once per BPMN process after its workflow module was deployed. Reading
 * an old model belongs to the adapter ({@link ProcessVersionCatalog}), deciding
 * whether a method serves it belongs to the core, and the two ends meet here.
 * <p>
 * "Older than what this boot deployed" has two readings, and both are ordinary. Where a
 * model was deployed under that id, the version the BPMS assigned to it is the border.
 * Where the application DECLARES the id without bringing a model for it - what renaming a
 * BPMN process leaves behind, the old id living on in the BPMS with the workflows still
 * running on it - there is no border and every version the BPMS holds is an older one.
 * <p>
 * How loud a finding is depends on whether workflows still run on that version: a
 * version nobody runs is a warning, a version with running workflows is FATAL and,
 * where the operator asked for it, the end of the boot.
 * <p>
 * Why the core drives this check while an adapter only answers two questions is decision 15 in the
 * repository's DECISIONS.md.
 *
 * <h2>What one run of it costs</h2>
 *
 * One question for the versions the BPMS holds, and then two per version OLDER than the one this
 * boot deployed: the model of that version, and how many workflows still run on it. A third is
 * asked only where workflows do run on such a version - which of its elements can put a second
 * token into one of them - so a version nobody is on costs nothing extra. The cost therefore
 * follows the number of versions, which grows when somebody deploys a changed model and which
 * <code>outfaded-versions</code> is the operator's way to bound. It does not follow the number of
 * workflows, and it must not start to - decision 19.
 * <p>
 * A BPMN process id the application declares without deploying a model under it is one such
 * process more, asked about like any other. That number follows the declarations of the
 * application, which change when somebody edits them.
 */
public class DeployedProcessVersionsCheck {

  /**
   * Which of the given tasks no <code>&#64;WorkflowTask</code> method serves in that
   * version - answered by the {@link WorkflowTaskRegistry}, which is the only place
   * knowing the version ranges of the methods.
   */
  @FunctionalInterface
  public interface UnservedTasks {

    /**
     * Picks the tasks of one held version which nothing would serve.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param version The version identifier the BPMS reported, which is what the version
     *          ranges of the methods are matched against
     * @param tasks The tasks of that version, read from the model the BPMS still holds
     * @return Those of them no method serves, empty where the version is fully served
     */
    Collection<BpmnTaskSpec> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        Collection<BpmnTaskSpec> tasks);

  }

  /**
   * Which methods registered for one BPMN process serve none of the versions worth
   * serving, in that process and in every other BPMN process of the workflow module they
   * are registered for - answered by the {@link WorkflowTaskRegistry} for all three
   * annotations carrying a <code>version</code> attribute.
   */
  @FunctionalInterface
  public interface DeadHandlers {

    /**
     * Names the methods registered for that process which serve nothing worth serving.
     * <p>
     * The whole module is handed over, not just the process being asked about, because a
     * method is registered once per BPMN process its class declares. A method which serves no
     * version of one process may be the one kept for another, and calling it dead would send
     * a developer to remove the code which keeps the running workflows alive.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID whose methods are judged
     * @param servableVersionsByProcess What every BPMN process of that module can be served
     *          with - what the BPMS holds minus the versions the configuration faded out
     * @return The methods, worded for the message, empty where every method serves something
     */
    List<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        java.util.Map<String, Collection<String>> servableVersionsByProcess);

  }

  /**
   * The elements of the versions a BPMS still holds which can put a second token into a
   * running workflow - handed to the {@link WorkflowTaskRegistry}, which is the only place
   * knowing the workflow aggregate of a BPMN process and therefore the only one which can
   * decide what the finding means.
   */
  @FunctionalInterface
  public interface ConcurrentTokenElementsOfHeldVersions {

    /**
     * Hands over what was found in the held versions of one BPMN process, once per process.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param elementIdsByVersion The element ids per held version, and only for a version
     *          workflows still run on - empty where nothing was found, which is the normal
     *          case and gets no message
     */
    void report(
        String workflowModuleId,
        String bpmnProcessId,
        java.util.Map<String, Collection<String>> elementIdsByVersion);

  }

  /**
   * Which elements a task of one held version iterates without naming the item, although a
   * method serving that version reads it - answered by the {@link WorkflowTaskRegistry},
   * which is the only place knowing what a method asks for.
   */
  @FunctionalInterface
  public interface ItemsAHeldVersionNeverNames {

    /**
     * Judges the multi-instance shape of ONE task of ONE held version.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param version The version identifier the BPMS reported, which is what the version
     *          ranges of the methods are matched against
     * @param task The task, as the model that version holds describes it
     * @return The element ids whose item a method serving that version reads and that
     *         version's model never names, empty where nothing is wrong and where the
     *         adapter does not read the shape at all
     */
    Collection<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        BpmnTaskSpec task);

  }

  /**
   * The identifiers a version a BPMS still holds declares - handed to the place which knows
   * what the current deployment scopes them to, so the name a workflow module deployed years
   * ago can be held against the module which uses it today.
   */
  @FunctionalInterface
  public interface IdentifiersOfHeldVersions {

    /**
     * Hands over the identifiers of ONE held version, while that version's model is read.
     *
     * @param adapterId The adapter ID whose BPMS holds the version
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param version The version identifier the BPMS reported
     * @param activeWorkflows How many workflows still run on it, <code>null</code> where that
     *          BPMS cannot count - the number says how urgent a finding is, because a name of
     *          a version workflows run on is live rather than dormant
     * @param declared The plain identifiers that version declares, never <code>null</code>
     */
    void report(
        String adapterId,
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        Long activeWorkflows,
        Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> declared);

  }

  private final ProcessVersions processVersions;

  private final OutfadedProcessVersions outfadedVersions;

  private final UnservedTasks unservedTasks;

  private final DeadHandlers deadHandlers;

  /**
   * What the application declared and what was really deployed - the second reading of
   * "older version" depends on it.
   */
  private final DeclaredBpmnProcesses declaredProcesses;

  /**
   * Where the elements of a held version which can produce a second token are judged.
   */
  private final ConcurrentTokenElementsOfHeldVersions concurrentTokenElements;

  /**
   * Where the identifiers of a held version are held against what the current deployment
   * declares. Absent where no platform wired it, and the question is then not even asked.
   */
  private final IdentifiersOfHeldVersions identifiersOfHeldVersions;

  /**
   * Where the multi-instance shape of a held version is held against what the methods
   * serving it read. Absent where no platform wired it.
   */
  private final ItemsAHeldVersionNeverNames itemsAHeldVersionNeverNames;

  /**
   * Where a finding which the start survives is left, so the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * What every BPMN process of a workflow module can be served with, collected while the
   * processes are checked one by one - see {@link #reportDeadHandlers(String)}, whose
   * verdict belongs to the whole module.
   */
  private final java.util.Map<String, List<HeldVersions>> heldVersionsPerModule = new ConcurrentHashMap<>();

  /**
   * What one BPMS holds for one BPMN process: everything, what of it is worth serving,
   * and what the configuration faded out.
   *
   * @param adapterId The adapter ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param all Every version that BPMS holds, the one this boot deployed included
   * @param servable Those of them the configuration does not fade out
   * @param outfaded The rest
   */
  private record HeldVersions(
                              String adapterId,
                              String bpmnProcessId,
                              List<String> all,
                              List<String> servable,
                              List<String> outfaded) {
  }

  /**
   * The check without the two reports which need a model of a held version read for them.
   * <p>
   * What is left is the version judgement itself: which tasks of an older version nobody
   * serves, which methods serve nothing, and how many workflows are on an older version.
   *
   * @param processVersions What the BPMS reported about their versions
   * @param outfadedVersions Which versions the operator declared obsolete
   * @param unservedTasks Which tasks of a held version no method serves
   * @param deadHandlers Which methods of the module serve nothing worth serving -
   *          <code>null</code> switches that report off, and nothing is then remembered for it
   * @param declaredProcesses What the application declared and what was really deployed,
   *          which is what tells the two readings of "older version" apart
   * @param findings Where a finding the start survives is left
   */
  public DeployedProcessVersionsCheck(
      final ProcessVersions processVersions,
      final OutfadedProcessVersions outfadedVersions,
      final UnservedTasks unservedTasks,
      final DeadHandlers deadHandlers,
      final DeclaredBpmnProcesses declaredProcesses,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this(processVersions, outfadedVersions, unservedTasks, deadHandlers, declaredProcesses, null, null, null, findings);

  }

  /**
   * The check with the second-token report, but without the identifiers of a held version.
   *
   * @param processVersions What the BPMS reported about their versions
   * @param outfadedVersions Which versions the operator declared obsolete
   * @param unservedTasks Which tasks of a held version no method serves
   * @param deadHandlers Which methods of the module serve nothing worth serving
   * @param declaredProcesses What the application declared and what was really deployed
   * @param concurrentTokenElements Where the elements of a held version which can produce a
   *          second token are judged - <code>null</code> leaves those models unread
   * @param findings Where a finding the start survives is left
   */
  public DeployedProcessVersionsCheck(
      final ProcessVersions processVersions,
      final OutfadedProcessVersions outfadedVersions,
      final UnservedTasks unservedTasks,
      final DeadHandlers deadHandlers,
      final DeclaredBpmnProcesses declaredProcesses,
      final ConcurrentTokenElementsOfHeldVersions concurrentTokenElements,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this(processVersions, outfadedVersions, unservedTasks, deadHandlers, declaredProcesses, concurrentTokenElements, null, null, findings);

  }

  /**
   * The check with the identifiers of a held version, but without its multi-instance shape -
   * what the tests of the name-clash report build.
   *
   * @param processVersions What the BPMS reported about their versions
   * @param outfadedVersions Which versions the operator declared obsolete
   * @param unservedTasks Which tasks of a held version no method serves
   * @param deadHandlers Which methods of the module serve nothing worth serving
   * @param declaredProcesses What the application declared and what was really deployed
   * @param concurrentTokenElements Where the elements of a held version which can produce a
   *          second token are judged
   * @param identifiersOfHeldVersions Where the identifiers of a held version are held against
   *          what this deployment scopes the same names to
   * @param findings Where a finding the start survives is left
   */
  public DeployedProcessVersionsCheck(
      final ProcessVersions processVersions,
      final OutfadedProcessVersions outfadedVersions,
      final UnservedTasks unservedTasks,
      final DeadHandlers deadHandlers,
      final DeclaredBpmnProcesses declaredProcesses,
      final ConcurrentTokenElementsOfHeldVersions concurrentTokenElements,
      final IdentifiersOfHeldVersions identifiersOfHeldVersions,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this(processVersions, outfadedVersions, unservedTasks, deadHandlers, declaredProcesses, concurrentTokenElements, identifiersOfHeldVersions, null, findings);

  }

  /**
   * The full check, which is what the {@link WorkflowTaskRegistry} builds while it is wired.
   * <p>
   * Every report is optional on purpose. A caller which does not want one hands
   * <code>null</code> for it, and the question behind it is then not even asked - which
   * matters here, because asking means reading a model the BPMS holds.
   *
   * @param processVersions What the BPMS reported about their versions
   * @param outfadedVersions Which versions the operator declared obsolete
   * @param unservedTasks Which tasks of a held version no method serves
   * @param deadHandlers Which methods of the module serve nothing worth serving
   * @param declaredProcesses What the application declared and what was really deployed
   * @param concurrentTokenElements Where the elements of a held version which can produce a
   *          second token are judged
   * @param identifiersOfHeldVersions Where the identifiers of a held version are held against
   *          what this deployment scopes the same names to - <code>null</code> where no
   *          platform wired the name-clash check
   * @param itemsAHeldVersionNeverNames Where the multi-instance shape of a held version is
   *          held against what the methods serving it read - <code>null</code> switches
   *          that question off
   * @param findings Where a finding the start survives is left
   */
  public DeployedProcessVersionsCheck(
      final ProcessVersions processVersions,
      final OutfadedProcessVersions outfadedVersions,
      final UnservedTasks unservedTasks,
      final DeadHandlers deadHandlers,
      final DeclaredBpmnProcesses declaredProcesses,
      final ConcurrentTokenElementsOfHeldVersions concurrentTokenElements,
      final IdentifiersOfHeldVersions identifiersOfHeldVersions,
      final ItemsAHeldVersionNeverNames itemsAHeldVersionNeverNames,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this.processVersions = processVersions;
    this.outfadedVersions = outfadedVersions;
    this.unservedTasks = unservedTasks;
    this.deadHandlers = deadHandlers;
    this.declaredProcesses = declaredProcesses;
    this.concurrentTokenElements = concurrentTokenElements;
    this.identifiersOfHeldVersions = identifiersOfHeldVersions;
    this.itemsAHeldVersionNeverNames = itemsAHeldVersionNeverNames;
    this.findings = findings;

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
            registered.catalog(),
            resolver));

  }

  /**
   * The check for ONE adapter - the entry point of the tests, which hand in their own
   * {@link ProcessVersionCatalog}. What it finds about methods which never run is
   * remembered rather than reported: that verdict belongs to the whole workflow module
   * and is drawn by {@link #reportDeadHandlers(String)}.
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
      final ProcessVersionCatalog catalog,
      final VersionRange.ProcessVersionResolver resolver) {

    final var deployed = processVersions.deployedVersion(adapterId, workflowModuleId, bpmnProcessId);
    // an id the application declares without bringing a model for it has no newer
    // version to compare against, so everything the BPMS holds under it is older
    final var everyHeldVersionIsOlder = (deployed == null) && (declaredProcesses != null) && declaredProcesses
        .isDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId);
    if ((deployed == null) && !everyHeldVersionIsOlder) {
      // a BPMS counting no versions: there is no "older version" to speak of
      return;
    }
    if (deployed != null) {
      failIfDeployedVersionIsOutfaded(workflowModuleId, bpmnProcessId, adapterId, deployed, resolver);
    }

    final var known = catalog.deployedVersionsOf(workflowModuleId, bpmnProcessId);
    if ((known == null) || known.isEmpty()) {
      if (everyHeldVersionIsOlder) {
        reportDeclaredProcessNobodyHolds(workflowModuleId, bpmnProcessId, adapterId);
      }
      return;
    }
    rememberHeldVersions(workflowModuleId, bpmnProcessId, adapterId, known, deployed, resolver);
    // asking the BPMS how many workflows run on a version is a QUERY, and three of the
    // reports below want the same answer for the same version. Asked once per version
    // and per run of this check, and only for a version somebody actually asks about
    final var instanceCounts = new InstanceCounts(workflowModuleId, bpmnProcessId, catalog);
    final var olderVersions = everyHeldVersionIsOlder
        ? identifiersOf(known)
        : olderThan(known, deployed);
    reportWorkflowsOnOlderVersions(
        workflowModuleId, bpmnProcessId, adapterId, olderVersions, instanceCounts, catalog, everyHeldVersionIsOlder);
    final var concurrentTokensPerVersion = new java.util.LinkedHashMap<String, Collection<String>>();
    for (final var version : olderVersions) {
      if (outfadedVersions.isOutfaded(workflowModuleId, bpmnProcessId, adapterId, version, resolver)) {
        reportOutfadedVersionInUse(workflowModuleId, bpmnProcessId, adapterId, version, instanceCounts);
        continue;
      }
      rememberConcurrentTokenElements(
          workflowModuleId, bpmnProcessId, version, instanceCounts, catalog, concurrentTokensPerVersion);
      reportIdentifiersOfHeldVersion(
          workflowModuleId, bpmnProcessId, adapterId, version, instanceCounts, catalog);
      final var tasks = catalog.tasksOfVersion(workflowModuleId, bpmnProcessId, version);
      if (tasks == null) {
        reportUnableToReadModels(workflowModuleId, bpmnProcessId, adapterId);
        break;
      }
      reportItemsThisVersionNeverNames(
          workflowModuleId, bpmnProcessId, adapterId, version, tasks, instanceCounts);
      final var unserved = unservedTasks.of(workflowModuleId, bpmnProcessId, version, tasks);
      if ((unserved == null) || unserved.isEmpty()) {
        continue;
      }
      reportUnservedTasks(workflowModuleId, bpmnProcessId, adapterId, version, unserved, instanceCounts);
    }
    if (concurrentTokenElements != null) {
      concurrentTokenElements.report(workflowModuleId, bpmnProcessId, concurrentTokensPerVersion);
    }

  }

  /**
   * Reads the identifiers ONE held version declares and hands them to the place which knows
   * what the current deployment scopes the same names to. A message name of a workflow
   * module deployed years ago lives only in that model, so this is the only place it can be
   * compared at all.
   * <p>
   * It runs in the loop which reads that version's model anyway, which is what keeps it
   * cheap: one question more about a model already being fetched, per version the check
   * already looks at.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param version The version identifier the BPMS reported
   * @param instanceCounts How many workflows run on a version, asked once per version
   * @param catalog What that BPMS can tell about the process
   */
  private void reportIdentifiersOfHeldVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final InstanceCounts instanceCounts,
      final ProcessVersionCatalog catalog) {

    if (identifiersOfHeldVersions == null) {
      return;
    }
    final var declared = catalog.identifiersOfVersion(workflowModuleId, bpmnProcessId, version);
    if (declared == null) {
      return; // this BPMS cannot read the model, so nothing is judged by an answer nobody has
    }
    identifiersOfHeldVersions
        .report(
            adapterId,
            workflowModuleId,
            bpmnProcessId,
            version,
            instanceCounts.of(version),
            declared);

  }

  /**
   * Reads the elements of ONE held version which can put a second token into a running
   * workflow, and remembers them for the one report the whole BPMN process gets.
   * <p>
   * Only asked where workflows really run on that version: a version nobody is on can lose
   * nobody's update, and the count is the number the reports around this one already have.
   * A BPMS which cannot say how many workflows run on a version is asked anyway - "cannot
   * tell" is not "nobody", and losing an update silently is the one outcome worth a model
   * read.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version identifier the BPMS reported
   * @param instanceCounts How many workflows run on a version, asked once per version
   * @param catalog What that BPMS can tell about the process
   * @param concurrentTokensPerVersion What was found so far, per version
   */
  private void rememberConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final InstanceCounts instanceCounts,
      final ProcessVersionCatalog catalog,
      final java.util.Map<String, Collection<String>> concurrentTokensPerVersion) {

    if (concurrentTokenElements == null) {
      return;
    }
    final var running = instanceCounts.of(version);
    if ((running != null) && (running == 0)) {
      return;
    }
    final var elements = catalog.concurrentTokenElementsOfVersion(workflowModuleId, bpmnProcessId, version);
    if ((elements == null) || elements.isEmpty()) {
      return;
    }
    concurrentTokensPerVersion.put(version, elements);

  }

  /**
   * Says how many workflows still run on a version older than the one this boot
   * deployed, and what those workflows will not get.
   *
   * <h4>Why this is worth a line even when everything is served</h4>
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
   * <h4>Why the number is trustworthy</h4>
   *
   * An older version exists only where the deployed model DIFFERS from what was deployed
   * before, and an adapter rewrites a model only to add something. So the same rewrite
   * which produced the new version is what the older workflows lack, and where nothing
   * was rewritten there is no older version and nothing is missing. The two questions
   * have one answer, which is why counting the versions answers both.
   *
   * <p>
   * What the older workflows lack is BPMS-specific, so it is not spelled out here: the
   * adapter reports it through {@link ProcessVersionCatalog}, and an adapter which
   * attaches its behaviour while parsing rather than while deploying answers nothing,
   * because for it nothing is missing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param olderVersions The versions older than the deployed one
   * @param instanceCounts How many workflows run on a version, asked once per version
   * @param catalog What that BPMS can tell about the process
   * @param nothingDeployedUnderThatId Whether the application declares this BPMN process
   *          without bringing a model for it, which is what a rename leaves behind
   */
  private void reportWorkflowsOnOlderVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final List<String> olderVersions,
      final InstanceCounts instanceCounts,
      final ProcessVersionCatalog catalog,
      final boolean nothingDeployedUnderThatId) {

    if (olderVersions.isEmpty()) {
      return;
    }
    var total = 0L;
    var counted = false;
    for (final var version : olderVersions) {
      final var running = instanceCounts.of(version);
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
    final var missing = catalog.whatOlderVersionsMiss(workflowModuleId, bpmnProcessId);
    final var whatThoseWorkflowsMiss = (missing == null) || missing.isBlank()
        ? ""
        : ": ".concat(missing);
    final var scope = "process '%s' of workflow module '%s', adapter '%s'"
        .formatted(bpmnProcessId, workflowModuleId, adapterId);
    if (nothingDeployedUnderThatId) {
      findings
          .notice(
              io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
              scope,
              """
                  %d workflow(s) still run on this BPMN process, which this application does not \
                  deploy any more - the adapter holds %d version(s) of it: %s. They keep being \
                  served because a @WorkflowService declares that id (secondaryBpmnProcesses), \
                  which is how a renamed BPMN process stays served, so this is not a defect. \
                  Whatever a newer model added reaches the version it was deployed as and no \
                  earlier one, so those workflows never get it%s. The number falls to zero as \
                  they end, and it is what tells you when the declaration and the methods serving \
                  it can go."""
                  .formatted(
                      total,
                      olderVersions.size(),
                      String.join(", ", olderVersions),
                      whatThoseWorkflowsMiss));
      return;
    }
    findings
        .notice(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            scope,
            """
                %d workflow(s) of this BPMN process still run on %d version(s) older than the \
                one the adapter deployed during this boot: %s. They keep being served - this is \
                not a defect - but whatever this version added TO THE MODEL reaches the version it \
                deployed and no earlier one, so those workflows never get it%s. The number falls \
                to zero as they end, and it is what tells you when the difference is gone."""
                .formatted(
                    total,
                    olderVersions.size(),
                    String.join(", ", olderVersions),
                    whatThoseWorkflowsMiss));

  }

  /**
   * Reports a BPMN process id the application declares although the BPMS holds nothing
   * under it - not a failure: it is what an old id looks like once its last workflow
   * ended, and it is also what a typo looks like.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID nothing was deployed under
   * @param adapterId The adapter ID
   */
  private void reportDeclaredProcessNobodyHolds(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s', adapter '%s'"
                .formatted(bpmnProcessId, workflowModuleId, adapterId),
            """
                A @WorkflowService declares this BPMN process (secondaryBpmnProcesses), but this \
                application deploys no model under that id and the adapter holds no version of it \
                either - nothing this application does reaches that id. Where the process was \
                renamed, this is what the old id looks like once its last workflow has ended: the \
                declaration and the methods kept for it can go. Otherwise check the spelling \
                against the BPMN process ids this workflow module deploys: %s."""
                .formatted(deployedProcessIdsOf(workflowModuleId)));

  }

  /**
   * The BPMN process ids of that workflow module a model WAS deployed under during this
   * boot - what a developer compares a declared id which reaches nothing against.
   */
  private String deployedProcessIdsOf(
      final String workflowModuleId) {

    final var deployed = declaredProcesses
        .deployedProcessesOf(workflowModuleId)
        .stream()
        .map("'%s'"::formatted)
        .collect(Collectors.joining(", "));
    return deployed.isEmpty()
        ? "none"
        : deployed;

  }

  /**
   * Remembers what one BPMS holds for one BPMN process, for the dead-handler report of the
   * whole workflow module. "Worth serving" is what the BPMS holds minus what the
   * configuration faded out, so fading out a version also tells the developer which methods
   * just became pointless - the code-side counterpart of
   * <code>outfaded-versions-in-use</code>, which speaks about running workflows only.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param known The versions that BPMS holds
   * @param deployed The version this boot deployed, or <code>null</code> where nothing was
   *          deployed under that id
   * @param resolver Resolves version tags of that process
   */
  private void rememberHeldVersions(
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
    final var allHeld = (deployed == null) || heldVersions.contains(deployed)
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
    heldVersionsPerModule
        .computeIfAbsent(workflowModuleId, module -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(new HeldVersions(adapterId, bpmnProcessId, allHeld, servable, outfaded));

  }

  /**
   * Reports the methods of a workflow module which serve no version worth serving - once
   * the versions of every BPMN process of that module were read, because that is what the
   * verdict needs.
   * <p>
   * A method is registered once per BPMN process its class declares, so a method which
   * serves no version of one process may well be the one kept for another. That is the
   * whole point of a declaration a renamed process leaves behind: the versions under the
   * old id are what those methods exist for, and calling them dead would send a developer
   * to remove exactly the code which keeps the running workflows alive. Which is why this
   * is one statement per module rather than one per process, and why the registry gets the
   * versions of all of them ({@link DeadHandlers}).
   *
   * @param workflowModuleId The workflow module whose processes were checked
   */
  public void reportDeadHandlers(
      final String workflowModuleId) {

    final var held = heldVersionsPerModule.remove(workflowModuleId);
    if ((held == null) || (deadHandlers == null)) {
      return;
    }
    final var servableVersionsByProcess = new java.util.LinkedHashMap<String, Collection<String>>();
    held
        .forEach(versions -> servableVersionsByProcess
            .merge(
                versions.bpmnProcessId(),
                versions.servable(),
                (
                    alreadyKnown,
                    ofAnotherAdapter) -> java.util.stream.Stream
                        .concat(alreadyKnown.stream(), ofAnotherAdapter.stream())
                        .distinct()
                        .toList()));
    held
        .forEach(versions -> deadHandlers
            .of(workflowModuleId, versions.bpmnProcessId(), servableVersionsByProcess)
            .stream()
            .forEach(handler -> findings
                .warn(
                    io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
                    "process '%s' of workflow module '%s', adapter '%s'".formatted(
                        versions.bpmnProcessId(),
                        workflowModuleId,
                        versions.adapterId()),
                    """
                        The %s matches no version this adapter holds%s - the method never runs. \
                        Widen its version range, remove the method, or deploy a version it \
                        serves."""
                        .formatted(
                            handler,
                            versions.outfaded().isEmpty()
                                ? " (held: %s)".formatted(String.join(", ", versions.all()))
                                : " (held: %s, of which %s %s faded out by '%s')".formatted(
                                    String.join(", ", versions.all()),
                                    String.join(", ", versions.outfaded()),
                                    versions.outfaded().size() == 1
                                        ? "is"
                                        : "are",
                                    OutfadedProcessVersions.propertyName(versions.adapterId()))))));

  }

  /**
   * The version identifiers of what a BPMS holds, in deployment order.
   */
  private static List<String> identifiersOf(
      final List<DeployedProcessVersion> known) {

    return known
        .stream()
        .map(DeployedProcessVersion::version)
        .filter(java.util.Objects::nonNull)
        .toList();

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
      final InstanceCounts instanceCounts) {

    final var definitions = unserved
        .stream()
        .map(task -> "'%s'".formatted(task.taskDefinition() == null
            ? task.activityId()
            : task.taskDefinition()))
        .distinct()
        .collect(Collectors.joining(", "));
    final var running = instanceCounts.of(version);
    final var remedy = """
        Add a @WorkflowTask method whose version range covers version '%s', or declare that version \
        obsolete by adding e.g. '%s' to '%s'."""
        .formatted(version, version, OutfadedProcessVersions.propertyName(adapterId));

    final var scope = "version '%s' of process '%s' of workflow module '%s', adapter '%s'"
        .formatted(version, bpmnProcessId, workflowModuleId, adapterId);
    if ((running != null) && (running > 0)) {
      findings
          .error(
              io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
              scope,
              """
                  %d workflow(s) still run on this version, whose task definition(s) %s are \
                  served by NO @WorkflowTask method of this application - each of them will fail \
                  with an incident at its next such task! %s"""
                  .formatted(running, definitions, remedy));
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            scope,
            """
                This version is still deployed at the adapter and its task definition(s) %s are \
                served by NO @WorkflowTask method of this application%s. %s"""
                .formatted(
                    definitions,
                    running == null
                        ? ", and this BPMS cannot say whether workflows still run on it"
                        : ", no workflow runs on it right now",
                    remedy));

  }

  /**
   * Says where a method serving a held version reads the item of an element that version's
   * model never names.
   * <p>
   * While a model is DEPLOYED, the adapter asks the same question and refuses the pairing:
   * a handler reading an item which the element does not carry gets <code>null</code> and
   * nothing says why. A version a BPMS only still holds is never deployed again, so nobody
   * asks - and the methods of the application serve it all the same, because their version
   * ranges say so. The first news is then a <code>null</code> in a handler running on an
   * old workflow.
   * <p>
   * A WARNING and not a refusal, and the reason is the one every finding about a held
   * version has: nobody can change that model any more, and the application may have
   * decided on purpose to let the item be <code>null</code> there. What it must not be is
   * silent.
   * <p>
   * An adapter which does not read the shape of a held version answers <code>null</code>
   * for it, and then nothing is judged - decision 38 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID whose BPMS holds the version
   * @param version The version identifier the BPMS reported
   * @param tasks The tasks of that version, read from the model the BPMS still holds
   * @param instanceCounts How many workflows run on a version, asked once per version
   */
  private void reportItemsThisVersionNeverNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final Collection<BpmnTaskSpec> tasks,
      final InstanceCounts instanceCounts) {

    if (itemsAHeldVersionNeverNames == null) {
      return;
    }
    final var unnamed = new java.util.LinkedHashMap<String, java.util.Set<String>>();
    for (final var task : tasks) {
      if (task.multiInstanceElementsWithoutAnItem() == null) {
        // this adapter does not read the shape of a held version, so there is nothing to
        // hold the methods against
        continue;
      }
      final var elements = itemsAHeldVersionNeverNames
          .of(workflowModuleId, bpmnProcessId, version, task);
      if ((elements == null) || elements.isEmpty()) {
        continue;
      }
      unnamed
          .computeIfAbsent(
              task.taskDefinition() == null
                  ? task.activityId()
                  : task.taskDefinition(),
              definition -> new java.util.LinkedHashSet<>())
          .addAll(elements);
    }
    if (unnamed.isEmpty()) {
      return;
    }
    final var whatReadsWhat = unnamed
        .entrySet()
        .stream()
        .map(entry -> "'%s' reads the item of %s"
            .formatted(
                entry.getKey(),
                entry
                    .getValue()
                    .stream()
                    .map("'%s'"::formatted)
                    .collect(Collectors.joining(", "))))
        .collect(Collectors.joining("; "));
    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s', adapter '%s'"
                .formatted(bpmnProcessId, workflowModuleId, adapterId),
            """
                Version '%s' iterates without naming the value of a round, and a @WorkflowTask method \
                serving that version reads it: %s. The parameter is null on every workflow still \
                running on that version%s, and nothing else says why. The model of a version a BPMS \
                holds cannot be changed any more, so the way out is on the side of the code: narrow \
                the version range of the method and add one for the old version which reads the index \
                and the total only, or leave it as it is if a null item is what that version is meant \
                to give.\
                """
                .formatted(version, whatReadsWhat, workflowsRunningOn(instanceCounts.of(version))));

  }

  /**
   * How many workflows a finding about a held version is about, in the words the count
   * allows - a BPMS which cannot count says that instead of a number.
   */
  private static String workflowsRunningOn(
      final Long running) {

    if (running == null) {
      return ", and this BPMS cannot say how many that are";
    }
    if (running == 0L) {
      return ", which is no workflow at the moment";
    }
    return running == 1L
        ? ", which is one workflow at the moment"
        : ", which is %d workflows at the moment".formatted(running);

  }

  private void reportOutfadedVersionInUse(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final InstanceCounts instanceCounts) {

    final var running = instanceCounts.of(version);
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
      findings
          .refuse(
              io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
              "version '%s' of process '%s' of workflow module '%s', adapter '%s'"
                  .formatted(version, bpmnProcessId, workflowModuleId, adapterId),
              message);
      return;
    }
    findings
        .error(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "version '%s' of process '%s' of workflow module '%s', adapter '%s'"
                .formatted(version, bpmnProcessId, workflowModuleId, adapterId),
            message);

  }

  private void reportUnableToReadModels(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s', adapter '%s'"
                .formatted(bpmnProcessId, workflowModuleId, adapterId),
            """
                This adapter cannot read the models of the older versions of this BPMN process its \
                BPMS still holds, so VanillaBP cannot tell whether this application still serves \
                them - the adapter's own log says why. Workflows running on such a version fail \
                with an incident at a task no @WorkflowTask method serves, which is what this \
                check exists to report before it happens.""");

  }

  private void reportUnableToTellAboutInstances(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s', adapter '%s'"
                .formatted(bpmnProcessId, workflowModuleId, adapterId),
            """
                This adapter cannot say how many workflows of this BPMN process still run on the \
                versions '%s' fades out - the adapter's own log says why. The versions stay faded \
                out; workflows still running on one of them fail with an incident at a task no \
                @WorkflowTask method serves."""
                .formatted(OutfadedProcessVersions.propertyName(adapterId)));

  }

  /**
   * The instance count of a version, asked at most ONCE per version and per run of this
   * check.
   * <p>
   * Three of the reports want the same number for the same version, and every one of
   * them is a query to the BPMS: a count over the engine's runtime table on Camunda 7, a
   * search on Camunda 8. Asking twice was waste which grew with the number of versions a
   * BPMS holds, and that number grows with every deployment which changes a model.
   * <p>
   * Lazy on purpose: a version nobody reports about is never asked for. <code>null</code>
   * is remembered like any other answer, because "this BPMS cannot say" does not become
   * true on a second attempt either.
   */
  private static final class InstanceCounts {

    private final String workflowModuleId;

    private final String bpmnProcessId;

    private final ProcessVersionCatalog catalog;

    private final java.util.Map<String, java.util.Optional<Long>> counts = new java.util.HashMap<>();

    private InstanceCounts(
        final String workflowModuleId,
        final String bpmnProcessId,
        final ProcessVersionCatalog catalog) {

      this.workflowModuleId = workflowModuleId;
      this.bpmnProcessId = bpmnProcessId;
      this.catalog = catalog;

    }

    private Long of(
        final String version) {

      return counts
          .computeIfAbsent(
              version,
              asked -> java.util.Optional
                  .ofNullable(catalog.activeInstanceCountOf(workflowModuleId, bpmnProcessId, asked)))
          .orElse(null);

    }

  }

}
