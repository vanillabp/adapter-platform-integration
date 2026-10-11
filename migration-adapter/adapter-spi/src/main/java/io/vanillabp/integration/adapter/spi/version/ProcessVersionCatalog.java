package io.vanillabp.integration.adapter.spi.version;

import java.util.List;

/**
 * What a BPMS knows about the deployed versions of a BPMN process. An adapter whose BPMS
 * can tell implements it. It hands it to the core during <code>wireBpmn</code> with
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions}.
 * <p>
 * The core needs it only for version SPECIFICATIONS which name a version TAG
 * (<code>&#64;WorkflowTask(version = "release-2024")</code>, <code>version = "&gt;v1.4"</code>).
 * A specification made of numbers is compared with the version the BPMS reported, without
 * asking anybody. So an adapter of a BPMS which cannot report tags just registers nothing.
 * Version specifications made of numbers keep working. A specification which names a tag
 * matches nothing, and it is reported once with a guiding message.
 * <p>
 * <b>Both directions should be cheap.</b> {@link #resolveVersion} is called while a task
 * is dispatched. So an implementation caches what it learned, and asks the BPMS only for
 * a version it has not seen yet. That case does happen. During a rolling deployment,
 * another cluster node may already have deployed a version this node does not know. The
 * BPMS then delivers a task of that version before this node deploys.
 * {@link CachingProcessVersionCatalog} implements exactly that and leaves the BPMS query
 * to the adapter.
 * <p>
 * Two questions here must be answered: {@link #deployedVersionsOf} and
 * {@link #resolveVersion}. The others are optional. Each has a default answer, which it
 * describes. Why an adapter answers questions here instead of running the check for
 * old process versions itself is decision 15 in the repository's DECISIONS.md.
 */
public interface ProcessVersionCatalog {

  /**
   * All versions of the given process which the BPMS knows, in the order of deployment,
   * oldest first. It is called once per process at startup, to resolve the version tags
   * which the annotations of the application name. At that time the deployment has
   * finished, so the version deployed by this start is included.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The deployed versions, oldest first; empty if the BPMS cannot tell
   */
  List<DeployedProcessVersion> deployedVersionsOf(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The version with the given identifier which the BPMS reported, or the newest version
   * with the given version tag.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param versionOrVersionTag A version identifier or a version tag
   * @return The version or <code>null</code> if the BPMS does not know it
   */
  DeployedProcessVersion resolveVersion(
      String workflowModuleId,
      String bpmnProcessId,
      String versionOrVersionTag);

  /**
   * The tasks of ONE deployed version of a BPMN process, read from the model the BPMS
   * still holds. The startup check for old process versions needs them, to tell whether
   * the application still serves that version. The specs are built exactly like the
   * ones handed to
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#validateTaskWiring},
   * so both directions of the wiring talk about the same thing.
   * <p>
   * Reading a model depends on the BPMS, and not every BPMS can do it. An adapter which
   * cannot returns <code>null</code>. That switches the check off for that adapter, and
   * does not pretend the version is fine. An adapter which CAN read models but finds
   * nothing for that version returns an empty collection.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The tasks of that version, or <code>null</code> if this BPMS cannot say
   */
  default java.util.Collection<io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec> tasksOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * The start events which a BPMS fires by itself (a timer, a signal, a condition) in ONE
   * deployed version of a BPMN process, read from the model the BPMS still holds. It is
   * the counterpart of {@link #tasksOfVersion} for the other direction of the wiring. The
   * specs are built exactly like the ones handed to
   * {@link io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker#validateBpmsInitiatedStarts},
   * so both talk about the same thing.
   * <p>
   * The core uses the answer to judge the <code>&#64;WorkflowStartedByBpms</code> methods
   * of a BPMN process id which the application DECLARES but deploys no model for. That is
   * the id a renamed process left behind. Nothing wires such an id during this start, so
   * otherwise nothing would judge those methods. But the BPMS may still fire the timer of
   * the old model every day. If a method names a start event which no held version has,
   * the core says so, and the method does not silently never run.
   * <p>
   * Reading a model depends on the BPMS, and not every BPMS can do it. An adapter which
   * cannot returns <code>null</code>. Then the core says nothing about that id, because it
   * has no answer to judge by. An adapter which CAN read models but finds no such start
   * event in that version returns an empty collection.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The BPMS-initiated start events of that version, or <code>null</code> if
   *         this BPMS cannot say
   */
  default java.util.Collection<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * The elements of ONE deployed version which can put a SECOND token into a running
   * workflow. These are a non-interrupting boundary event, a parallel or inclusive gateway
   * which forks into several flows, a parallel multi-instance activity, a non-interrupting
   * event subprocess and an ad-hoc subprocess. The adapter walks the model the same way it
   * does while wiring for
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportConcurrentTokenElements},
   * but over a model the BPMS still holds.
   * <p>
   * A check over the model of this start never sees the versions which run longest. An
   * older version may have a parallel gateway which the new model dropped. Its workflows
   * keep running as long as they take. Two branches which write one workflow aggregate
   * lose updates there, just as they would in the model just deployed. The core asks about
   * a version only if workflows still run on it. So a version nobody is on costs no model
   * read.
   * <p>
   * An adapter which cannot read a held model returns <code>null</code>. Nothing is
   * guessed from a missing answer. An adapter which read the model and found no such
   * element returns an empty collection.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The IDs of the elements producing a second token in that version, or
   *         <code>null</code> if this BPMS cannot say
   */
  default java.util.Collection<String> concurrentTokenElementsOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * The identifiers which ONE deployed version declares and which the workflow module
   * scopes, read from the model the BPMS still holds. These are message names, signal
   * names, BPMN error codes, escalation codes, and task definitions if your BPMS scopes
   * those. Name the BPMN process on a task definition, because task definitions are scoped
   * per process. Leave it <code>null</code> on the rest. The names are the PLAIN ones, as
   * the application knows them. Strip your prefix the way
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport#plainIdentifier}
   * does, because the core builds the scoped forms itself.
   * <p>
   * The core uses the answer to ask whether a name of a version deployed years ago is the
   * name another workflow module uses today. Nothing else sees this clash. A model the BPMS
   * holds is the only place where such a name still lives, and no engine keeps an index of
   * message names. A job type of a version which workflows still run on is the one name
   * here which is still in use, so it matters most.
   * <p>
   * It is asked in the same loop as {@link #tasksOfVersion}. The loop goes over the
   * versions which are older than the one this start deployed and which are not faded out
   * by <code>outfaded-versions</code>. So the model of that version is read anyway, and on
   * an engine which caches parsed definitions this question fetches nothing more. Where
   * reading a model means fetching it over the network, an adapter which answers several
   * model questions per version should hold that model while the version is being asked
   * about, and drop it afterwards. The core asks each question once per version and keeps
   * no model of its own.
   * <p>
   * An adapter which cannot read a held model returns <code>null</code>, and the core says
   * nothing about that version. An adapter which read the model and found no such
   * identifier returns an empty collection.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The identifiers that version declares, or <code>null</code> if this BPMS
   *         cannot say
   */
  default java.util.Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> identifiersOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * How many workflows still run on ONE deployed version. This decides whether a task
   * definition of that version which nothing serves is a warning or a defect. It also
   * decides whether fading the version out (<code>outfaded-versions</code>) leaves
   * workflows behind.
   * <p>
   * A BPMS which cannot be asked returns <code>null</code>. The core then says so once,
   * with a guiding message. It does not let a question without an answer stop the start.
   * <p>
   * The BPMS does the counting: a <code>count()</code> on an engine, the total of a search
   * on a cluster. Fetching the workflows and counting them answers the same question, but
   * makes the start slower with every year the application runs. That is what decision
   * 19 in the repository's DECISIONS.md forbids. The core asks once per version and per
   * start, so an adapter needs no cache of its own.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The number of active workflows of that version, or <code>null</code> if
   *         this BPMS cannot say
   */
  default Long activeInstanceCountOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * What a workflow which runs on an OLDER version of this process does not get, in words
   * an operator can act on. The adapter names it, because only the adapter knows what it
   * attaches and how.
   * <p>
   * An adapter can bring the behaviour of VanillaBP to a workflow in two ways, and they
   * differ exactly here. An adapter which attaches while the engine PARSES a process
   * definition reaches every version its BPMS holds. Nothing is missing, so it answers
   * <code>null</code>. An adapter which writes into the MODEL it deploys reaches only the
   * version it deployed, no earlier one. So it names what those workflows will never get.
   * <p>
   * It is asked once per BPMN process at startup, and only if workflows really run on an
   * older version. The default answers <code>null</code>, so the report only shows the
   * count.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return A sentence naming what is missing, or <code>null</code> where nothing is
   */
  default String whatOlderVersionsMiss(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return null;

  }

}
