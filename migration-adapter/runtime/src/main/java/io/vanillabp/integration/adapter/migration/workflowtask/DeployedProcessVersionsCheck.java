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
 * The startup check for old process versions. A BPMS keeps every version of a process it
 * was ever given, and workflows keep running on them. But the application only brings the
 * newest model. So while it boots, it is worth asking whether the application still SERVES
 * the older versions. Without this check, the first news of a version which nobody serves
 * is an incident on a live workflow.
 * <p>
 * The check runs once per BPMN process, after its workflow module was deployed. Reading an
 * old model is the adapter's job ({@link ProcessVersionCatalog}). Deciding whether a method
 * serves it is the core's job. The two ends meet here.
 * <p>
 * "Older than what this boot deployed" can mean two things, and both are ordinary. Where a
 * model was deployed under that id, the version which the BPMS gave it is the limit. Where
 * the application DECLARES the id without bringing a model for it, there is no limit, and
 * every version the BPMS holds is an older one. That is what renaming a BPMN process leaves
 * behind: the old id lives on in the BPMS, with workflows still running on it.
 * <p>
 * The version which this boot deployed is not an older version, but it gets one question as
 * well: whether every task of it has a method for exactly that version. The wiring check
 * asks for a method before the deployment, when the version is not known yet. So a method
 * whose range misses the deployed version gets through it. Such a finding ends the start.
 * <p>
 * How loud a finding is depends on whether workflows still run on that version. A version
 * which nobody runs on is a warning. A version with running workflows is FATAL, and where
 * the operator asked for it, the end of the boot.
 * <p>
 * Decision 15 in the repository's DECISIONS.md says why the core drives this check, while an
 * adapter only answers two questions.
 *
 * <h2>What one run costs</h2>
 *
 * One question for the versions which the BPMS holds. Then two per version OLDER than the
 * one this boot deployed: the model of that version, and how many workflows still run on
 * it. A third question is asked only where workflows do run on such a version: which of its
 * elements can put a second token into one of them. So a version which nobody is on costs
 * nothing extra. The cost follows the number of versions. That number grows when somebody
 * deploys a changed model, and <code>outfaded-versions</code> is how the operator limits
 * it. The cost does not follow the number of workflows, and it must not start to (decision
 * 19 in the repository's DECISIONS.md).
 * <p>
 * A BPMN process id which the application declares without deploying a model under it is
 * one more such process, asked about like any other. That number follows the declarations
 * of the application, which change when somebody edits them.
 */
public class DeployedProcessVersionsCheck {

  /**
   * How a finding about one version of a process names it, with the version and the plain
   * BPMN process id to fill in. It starts the subject line of every finding about a version.
   * The workflow module and the adapter follow.
   * <p>
   * It is public so that a test of an adapter reads the words from here and fills in its
   * own values (see decision 105 in the repository's DECISIONS.md). Then a changed text
   * changes no adapter test, and a renamed constant breaks its build.
   */
  public static final String A_VERSION_OF_A_PROCESS = "version '%s' of process '%s'";

  /**
   * The words of the finding about a held version whose tasks no
   * <code>&#64;WorkflowTask</code> method serves. Both forms of that finding contain them: the
   * error about a version on which workflows still run, and the warning about one which
   * nobody is on.
   * <p>
   * Public for adapter tests, see {@link #A_VERSION_OF_A_PROCESS}.
   */
  public static final String SERVED_BY_NO_METHOD = "served by NO @WorkflowTask method of this application";

  /**
   * The words which say that workflows still run on a version whose tasks nothing serves.
   * Only the error contains them. So a test tells the error from the warning by them.
   * <p>
   * Public for adapter tests, see {@link #A_VERSION_OF_A_PROCESS}.
   */
  public static final String STILL_RUN_ON_THIS_VERSION = "still run on this version";

  /**
   * The words of the finding about a version which the configuration fades out while
   * workflows still run on it, with the version to fill in.
   * <p>
   * Public for adapter tests, see {@link #A_VERSION_OF_A_PROCESS}.
   */
  public static final String STILL_RUN_ON_AN_OUTFADED_VERSION = "still run on version '%s'";

  /**
   * The words of the message which ends the start where an element of the deployed model
   * has methods, but none for the version which the BPMS gave that model. The element is a
   * task with <code>&#64;WorkflowTask</code> methods, or an element which an extension
   * reported.
   * <p>
   * Public for adapter tests, see {@link #A_VERSION_OF_A_PROCESS}.
   */
  public static final String ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION = "Some of its elements are served by NO "
      + "method of this application in this version";

  /**
   * What a task without a method for the deployed version means. The message which ends the
   * start says it once.
   */
  public static final String A_TASK_WITHOUT_A_METHOD_STOPS_EVERY_WORKFLOW = "Every workflow started on this version "
      + "would stop at such a task with an incident.";

  /**
   * Which of the given tasks no <code>&#64;WorkflowTask</code> method serves in that
   * version. The {@link WorkflowTaskRegistry} answers it, because only it knows the version
   * ranges of the methods.
   */
  @FunctionalInterface
  public interface UnservedTasks {

    /**
     * Picks the tasks of one held version which nothing would serve.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param version The version identifier which the BPMS reported. The version ranges of
     *          the methods are matched against it
     * @param tasks The tasks of that version, read from the model the BPMS still holds
     * @return Those which no method serves, empty where the version is fully served
     */
    Collection<BpmnTaskSpec> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        Collection<BpmnTaskSpec> tasks);

  }

  /**
   * One element of the model this start deployed which has methods, but none for the version
   * which the BPMS gave that model.
   *
   * @param element The element, worded for the message, for example
   *          <code>task 'Activity_Review' (task definition 'review')</code>
   * @param methods The methods which serve the element in other versions, each with its
   *          version range and worded for the message
   * @param consequence What the missing method means, in one full sentence. Elements with the
   *          same sentence share it in the message
   */
  public record UnservedElement(
                                String element,
                                List<String> methods,
                                String consequence) {
  }

  /**
   * Which elements of the model this start deployed no method serves, in the version which
   * the BPMS gave that model. The {@link WorkflowTaskRegistry} answers it. It keeps the tasks
   * which the adapter wired, knows the version ranges of the methods, and asks the extensions
   * about the elements they reported.
   */
  @FunctionalInterface
  public interface ElementsTheDeployedVersionLeavesUnserved {

    /**
     * Picks the elements of the deployed model which no method serves in its version.
     *
     * @param adapterId The adapter which deployed the model
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param deployedVersion The version the BPMS gave the model this start deployed
     * @return Each element no method serves, empty where every element is served
     */
    List<UnservedElement> of(
        String adapterId,
        String workflowModuleId,
        String bpmnProcessId,
        String deployedVersion);

  }

  /**
   * Which methods registered for one BPMN process serve none of the versions worth serving,
   * in that process and in every other BPMN process of the workflow module they are
   * registered for. The {@link WorkflowTaskRegistry} answers it for all three annotations
   * with a <code>version</code> attribute.
   */
  @FunctionalInterface
  public interface DeadHandlers {

    /**
     * Names the methods registered for that process which serve nothing worth serving.
     * <p>
     * The whole module is handed over, not just the process being asked about. A method is
     * registered once for each BPMN process its class declares. A method which serves no
     * version of one process may be the one kept for another. Calling it dead would send a
     * developer to remove the code which keeps the running workflows alive.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID whose methods are judged
     * @param servableVersionsByProcess What every BPMN process of that module can be served
     *          with: what the BPMS holds, minus the versions the configuration faded out
     * @return The methods, worded for the message. Empty where every method serves
     *         something
     */
    List<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        java.util.Map<String, Collection<String>> servableVersionsByProcess);

  }

  /**
   * The elements of the versions which a BPMS still holds that can put a second token into a
   * running workflow. They are handed to the {@link WorkflowTaskRegistry}. Only it knows the
   * workflow aggregate of a BPMN process, so only it can decide what the finding means.
   */
  @FunctionalInterface
  public interface ConcurrentTokenElementsOfHeldVersions {

    /**
     * Hands over what was found in the held versions of one BPMN process, once per process.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param elementIdsByVersion The element ids per held version, and only for a version on
     *          which workflows still run. Empty where nothing was found. That is the normal
     *          case, and it gets no message
     */
    void report(
        String workflowModuleId,
        String bpmnProcessId,
        java.util.Map<String, Collection<String>> elementIdsByVersion);

  }

  /**
   * Which elements a task of one held version iterates without naming the item, although a
   * method which serves that version reads it. The {@link WorkflowTaskRegistry} answers it,
   * because only it knows what a method asks for.
   */
  @FunctionalInterface
  public interface ItemsAHeldVersionNeverNames {

    /**
     * Judges the multi-instance shape of ONE task of ONE held version.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param version The version identifier which the BPMS reported. The version ranges of
     *          the methods are matched against it
     * @param task The task, as the model that version holds describes it
     * @return The element ids whose item a method which serves that version reads, and which
     *         that version's model never names. Empty where nothing is wrong, and where the
     *         adapter does not read the shape at all
     */
    Collection<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        BpmnTaskSpec task);

  }

  /**
   * The identifiers which a version held by a BPMS declares. They are handed to the place
   * which knows to what the current deployment scopes them. So the name which a workflow
   * module deployed years ago can be checked against the module which uses it today.
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
     *          BPMS cannot count. The number says how urgent a finding is. A name of a version
     *          on which workflows run is live, not dormant
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
   * What the application declared, and what was really deployed. The second meaning of
   * "older version" depends on it.
   */
  private final DeclaredBpmnProcesses declaredProcesses;

  /**
   * Where the elements of a held version which can produce a second token are judged.
   */
  private final ConcurrentTokenElementsOfHeldVersions concurrentTokenElements;

  /**
   * Where the identifiers of a held version are checked against what the current deployment
   * declares. It is absent where no platform wired it. Then the question is not even
   * asked.
   */
  private final IdentifiersOfHeldVersions identifiersOfHeldVersions;

  /**
   * Where the multi-instance shape of a held version is checked against what the methods
   * which serve it read. It is absent where no platform wired it.
   */
  private final ItemsAHeldVersionNeverNames itemsAHeldVersionNeverNames;

  /**
   * Where the elements of the model this start deployed are checked against the version
   * which the BPMS gave it. It is absent where no platform wired it.
   */
  private final ElementsTheDeployedVersionLeavesUnserved elementsTheDeployedVersionLeavesUnserved;

  /**
   * Where a finding which the start survives goes, so that the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * What every BPMN process of a workflow module can be served with. It is collected while
   * the processes are checked one by one. See {@link #reportDeadHandlers(String)}, whose
   * verdict belongs to the whole module.
   */
  private final java.util.Map<String, List<HeldVersions>> heldVersionsPerModule = new ConcurrentHashMap<>();

  /**
   * What one BPMS holds for one BPMN process: everything, what of it is worth serving, and
   * what the configuration faded out.
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
   * The check without the two reports which need a model of a held version to be read.
   * <p>
   * What is left is the version judgement itself: which tasks of an older version nobody
   * serves, which methods serve nothing, and how many workflows are on an older version.
   *
   * @param processVersions What the BPMS reported about their versions
   * @param outfadedVersions Which versions the operator declared obsolete
   * @param unservedTasks Which tasks of a held version no method serves
   * @param deadHandlers Which methods of the module serve nothing worth serving.
   *          <code>null</code> switches that report off, and nothing is remembered for it
   * @param declaredProcesses What the application declared and what was really deployed.
   *          This tells the two meanings of "older version" apart
   * @param findings Where a finding which the start survives goes
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
   * The check with the report about second tokens, but without the identifiers of a held
   * version.
   *
   * @param processVersions What the BPMS reported about their versions
   * @param outfadedVersions Which versions the operator declared obsolete
   * @param unservedTasks Which tasks of a held version no method serves
   * @param deadHandlers Which methods of the module serve nothing worth serving
   * @param declaredProcesses What the application declared and what was really deployed
   * @param concurrentTokenElements Where the elements of a held version which can produce a
   *          second token are judged. <code>null</code> leaves those models unread
   * @param findings Where a finding which the start survives goes
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
   * The check with the identifiers of a held version, but without its multi-instance shape.
   * The tests of the name-clash report build this one.
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
   * @param findings Where a finding which the start survives goes
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
   * The check with every report about a held version, but without the check of the version
   * this start deployed. The tests of the multi-instance shape build this one.
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
   * @param itemsAHeldVersionNeverNames Where the multi-instance shape of a held version is
   *          held against what the methods serving it read
   * @param findings Where a finding which the start survives goes
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

    this(processVersions, outfadedVersions, unservedTasks, deadHandlers, declaredProcesses, concurrentTokenElements, identifiersOfHeldVersions, itemsAHeldVersionNeverNames, null, findings);

  }

  /**
   * The full check, which the {@link WorkflowTaskRegistry} builds while it is wired.
   * <p>
   * Every report is optional on purpose. A caller which does not want one hands in
   * <code>null</code> for it. Then the question behind it is not even asked. That matters
   * here, because asking means reading a model which the BPMS holds.
   *
   * @param processVersions What the BPMS reported about their versions
   * @param outfadedVersions Which versions the operator declared obsolete
   * @param unservedTasks Which tasks of a held version no method serves
   * @param deadHandlers Which methods of the module serve nothing worth serving
   * @param declaredProcesses What the application declared and what was really deployed
   * @param concurrentTokenElements Where the elements of a held version which can produce a
   *          second token are judged
   * @param identifiersOfHeldVersions Where the identifiers of a held version are checked
   *          against what this deployment scopes the same names to. <code>null</code> where
   *          no platform wired the name-clash check
   * @param itemsAHeldVersionNeverNames Where the multi-instance shape of a held version is
   *          checked against what the methods which serve it read. <code>null</code>
   *          switches that question off
   * @param elementsTheDeployedVersionLeavesUnserved Where the elements of the model this start
   *          deployed are checked against the version which the BPMS gave it.
   *          <code>null</code> switches that question off
   * @param findings Where a finding which the start survives goes
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
      final ElementsTheDeployedVersionLeavesUnserved elementsTheDeployedVersionLeavesUnserved,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this.processVersions = processVersions;
    this.outfadedVersions = outfadedVersions;
    this.unservedTasks = unservedTasks;
    this.deadHandlers = deadHandlers;
    this.declaredProcesses = declaredProcesses;
    this.concurrentTokenElements = concurrentTokenElements;
    this.identifiersOfHeldVersions = identifiersOfHeldVersions;
    this.itemsAHeldVersionNeverNames = itemsAHeldVersionNeverNames;
    this.elementsTheDeployedVersionLeavesUnserved = elementsTheDeployedVersionLeavesUnserved;
    this.findings = findings;

  }

  /**
   * Runs the check for one BPMN process, for every BPMS which serves it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @throws IllegalStateException If the configuration fades out the version this boot
   *           deployed. If an element of the version this boot deployed has no method for
   *           that version. Or if workflows run on an outfaded version and the policy is
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
   * The check for ONE adapter. The tests use it, and hand in their own
   * {@link ProcessVersionCatalog}. What it finds about methods which never run is remembered
   * and not reported. That verdict belongs to the whole workflow module, and
   * {@link #reportDeadHandlers(String)} draws it.
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
    // an id which the application declares without bringing a model for it has no newer
    // version to compare against. So everything the BPMS holds under it is older
    final var everyHeldVersionIsOlder = (deployed == null) && (declaredProcesses != null) && declaredProcesses
        .isDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId);
    if ((deployed == null) && !everyHeldVersionIsOlder) {
      // a BPMS which counts no versions: there is no "older version" to speak of
      return;
    }
    if (deployed != null) {
      failIfDeployedVersionIsOutfaded(workflowModuleId, bpmnProcessId, adapterId, deployed, resolver);
      failIfDeployedVersionLeavesAnElementUnserved(workflowModuleId, bpmnProcessId, adapterId, deployed);
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
    // reports below want the same answer for the same version. So it is asked once per
    // version and per run of this check, and only for a version which somebody really asks
    // about
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
   * Reads the identifiers which ONE held version declares, and hands them to the place which
   * knows to what the current deployment scopes the same names. A message name of a workflow
   * module deployed years ago lives only in that model. So this is the only place where it
   * can be compared at all.
   * <p>
   * It runs in the loop which reads that version's model anyway. That keeps it cheap: one
   * more question about a model which is being fetched already, per version which the check
   * looks at already.
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
   * workflow. It remembers them for the one report which the whole BPMN process gets.
   * <p>
   * It is only asked where workflows really run on that version. A version which nobody is on
   * can lose nobody's update. The reports around this one already have the count. A BPMS
   * which cannot say how many workflows run on a version is asked anyway. "Cannot tell" is
   * not "nobody", and losing an update silently is the one outcome which is worth reading a
   * model.
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
   * Says how many workflows still run on a version older than the one this boot deployed,
   * and what those workflows will not get.
   *
   * <h4>Why this is worth a line even when everything is served</h4>
   *
   * The rest of this check reports a DEFECT: a task definition which nobody serves, or a
   * version faded out while workflows are on it. This reports the normal case right after an
   * application was upgraded. Every task IS served, and the workflows are simply older than
   * the model. Nothing is wrong, but something is still worth knowing. A feature which an
   * adapter attaches to the MODEL it deploys cannot reach a workflow which was started
   * before, for the rest of that workflow's life. The number falls to zero by itself as those
   * workflows end. That makes it useful to an operator on the day of an upgrade.
   *
   * <h4>Why the number can be trusted</h4>
   *
   * An older version exists only where the deployed model DIFFERS from what was deployed
   * before. And an adapter rewrites a model only to add something. So the older workflows
   * lack exactly what the rewrite which produced the new version added. Where nothing was
   * rewritten, there is no older version, and nothing is missing. The two questions have one
   * answer. That is why counting the versions answers both.
   * <p>
   * What the older workflows lack depends on the BPMS, so it is not spelled out here. The
   * adapter reports it through {@link ProcessVersionCatalog}. An adapter which attaches its
   * behaviour while parsing, and not while deploying, answers nothing, because for it
   * nothing is missing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param olderVersions The versions older than the deployed one
   * @param instanceCounts How many workflows run on a version, asked once per version
   * @param catalog What that BPMS can tell about the process
   * @param nothingDeployedUnderThatId Whether the application declares this BPMN process
   *          without bringing a model for it. A rename leaves that behind
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
      // a BPMS which cannot count says so elsewhere already. And a version which nobody runs
      // on is not news
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
   * Reports a BPMN process id which the application declares although the BPMS holds nothing
   * under it. It is not a failure. An old id looks like this once its last workflow ended.
   * And a typo looks like this as well.
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
   * The BPMN process ids of that workflow module under which a model WAS deployed during this
   * boot. A developer compares a declared id which reaches nothing against them.
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
   * whole workflow module. "Worth serving" is what the BPMS holds, minus what the
   * configuration faded out. So fading out a version also tells the developer which methods
   * just became pointless. That is the code side of <code>outfaded-versions-in-use</code>,
   * which only speaks about running workflows.
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
    // the version which this boot deployed counts as held, even where the BPMS did not list
    // it
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
   * Reports the methods of a workflow module which serve no version worth serving. It runs
   * once the versions of every BPMN process of that module were read, because the verdict
   * needs them.
   * <p>
   * A method is registered once for each BPMN process its class declares. So a method which
   * serves no version of one process may well be the one kept for another. That is the whole
   * point of the declaration which a renamed process leaves behind. Those methods exist for
   * the versions under the old id. Calling them dead would send a developer to remove exactly
   * the code which keeps the running workflows alive. That is why this is one statement per
   * module and not one per process, and why the registry gets the versions of all of them
   * ({@link DeadHandlers}).
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
   * The versions which the BPMS holds that are OLDER than the one this boot deployed. The
   * catalog reports them in deployment order, so "older" means "before it in that list". The
   * catalog may not know the deployed version at all, for example after a failed query, or
   * on a BPMS which does not list what it just accepted. Then every other version is checked.
   * That errs towards checking too much rather than too little.
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

  /**
   * Ends the start where an element of the model this start deployed has methods, but none
   * for the version which the BPMS gave that model. The wiring check accepted the element,
   * because it runs before the deployment and cannot know the version. A call picks the
   * method by version, so no call of this version would find one. See decision 126 in the
   * repository's DECISIONS.md. An element of an extension counts as well, where its contract
   * asks for it. See decision 127 in the repository's DECISIONS.md.
   * <p>
   * A method which serves no version at all is still only a warning (decision 60 in the
   * repository's DECISIONS.md). The difference is the element. Here an element of the
   * deployed model is left without a method. There a method is left without an element.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter which deployed the model
   * @param deployed The version the BPMS gave the model this start deployed
   */
  private void failIfDeployedVersionLeavesAnElementUnserved(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String deployed) {

    if (elementsTheDeployedVersionLeavesUnserved == null) {
      return;
    }
    final var unserved = elementsTheDeployedVersionLeavesUnserved
        .of(adapterId, workflowModuleId, bpmnProcessId, deployed);
    if ((unserved == null) || unserved.isEmpty()) {
      return;
    }
    final var message = new StringBuilder(
        """
            Version '%s' of BPMN process '%s' (workflow module '%s') is the version adapter '%s' \
            deployed during this start. %s:"""
            .formatted(deployed, bpmnProcessId, workflowModuleId, adapterId,
                ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION));
    unserved
        .forEach(element -> message
            .append(
                "%n  - %s: %s"
                    .formatted(
                        element.element(),
                        element.methods().isEmpty()
                            ? "no method is wired to it."
                            : "%s, which does not cover version '%s'."
                                .formatted(String.join(", ", element.methods()), deployed))));
    // elements with the same consequence share its sentence. So a model with ten such tasks
    // reads it once
    unserved
        .stream()
        .map(UnservedElement::consequence)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(consequence -> message.append("%n%s".formatted(consequence)));
    message
        .append(
            """

                Do one of these:
                  - widen the version range of the method so it covers version '%s', e.g. version = ">=%s".
                  - add a method with the same annotation for version '%s'.
                  - deploy the model the method serves."""
                .formatted(deployed, deployed, deployed));
    throw new IllegalStateException(message.toString());

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

    final var scope = subjectOf(workflowModuleId, bpmnProcessId, adapterId, version);
    if ((running != null) && (running > 0)) {
      findings
          .error(
              io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
              scope,
              """
                  %d workflow(s) %s, whose task definition(s) %s are %s - each of them will fail \
                  with an incident at its next such task! %s"""
                  .formatted(running, STILL_RUN_ON_THIS_VERSION, definitions, SERVED_BY_NO_METHOD, remedy));
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            scope,
            """
                This version is still deployed at the adapter and its task definition(s) %s are \
                %s%s. %s"""
                .formatted(
                    definitions,
                    SERVED_BY_NO_METHOD,
                    running == null
                        ? ", and this BPMS cannot say whether workflows still run on it"
                        : ", no workflow runs on it right now",
                    remedy));

  }

  /**
   * Says where a method which serves a held version reads the item of an element that the
   * version's model never names.
   * <p>
   * While a model is DEPLOYED, the adapter asks the same question and refuses the pairing. A
   * handler which reads an item that the element does not have gets <code>null</code>, and
   * nothing says why. A version which a BPMS only still holds is never deployed again. So
   * nobody asks. But the methods of the application serve it all the same, because their
   * version ranges say so. The first news is then a <code>null</code> in a handler which
   * runs on an old workflow.
   * <p>
   * It is a WARNING and not a refusal, for the reason which every finding about a held version
   * has. Nobody can change that model any more, and the application may have decided on
   * purpose to let the item be <code>null</code> there. But it must not be silent.
   * <p>
   * An adapter which does not read the shape of a held version answers <code>null</code> for
   * it. Then nothing is judged (decision 38 in the repository's DECISIONS.md).
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
        // this adapter does not read the shape of a held version. So there is nothing to
        // check the methods against
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
   * How many workflows a finding about a held version is about, in the words which the count
   * allows. A BPMS which cannot count says that instead of a number.
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
        %d workflow(s) %s of BPMN process '%s' (workflow module '%s', adapter \
        '%s'), which '%s' fades out - this application does not serve that version any more, so each \
        of them will fail with an incident at its next task whose definition nobody serves! Complete \
        or migrate those workflows, or stop fading out that version. Set \
        'vanillabp.adapters.%s.outfaded-versions-in-use' to 'FAIL' to make this stop the application \
        instead of only reporting it."""
        .formatted(
            running,
            STILL_RUN_ON_AN_OUTFADED_VERSION.formatted(version),
            bpmnProcessId,
            workflowModuleId,
            adapterId,
            OutfadedProcessVersions.propertyName(adapterId),
            adapterId);
    if (outfadedVersions.policyFor(workflowModuleId, bpmnProcessId, adapterId) == OutfadedVersionsInUsePolicy.FAIL) {
      findings
          .refuse(
              io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
              subjectOf(workflowModuleId, bpmnProcessId, adapterId, version),
              message);
      return;
    }
    findings
        .error(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            subjectOf(workflowModuleId, bpmnProcessId, adapterId, version),
            message);

  }

  /**
   * The subject line of a finding about one version of a process.
   */
  private static String subjectOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version) {

    return "%s of workflow module '%s', adapter '%s'"
        .formatted(A_VERSION_OF_A_PROCESS.formatted(version, bpmnProcessId), workflowModuleId, adapterId);

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
   * Three of the reports want the same number for the same version, and each of them is a
   * query to the BPMS: a count over the engine's runtime table on Camunda 7, a search on
   * Camunda 8. Asking twice was waste. It grew with the number of versions which a BPMS
   * holds, and that number grows with every deployment which changes a model.
   * <p>
   * It is lazy on purpose, so a version which nobody reports about is never asked for.
   * <code>null</code> is remembered like any other answer. "This BPMS cannot say" does not
   * become true on a second attempt either.
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
