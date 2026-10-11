package io.vanillabp.integration.adapter.spi;

import java.util.Collection;

/**
 * Builds the identifiers a BPMS sees, following the {@link NameClashAvoidance} mode of
 * the workflow module. The core implements it ONCE, so every adapter scopes the same
 * way. The platform integration hands it to every adapter.
 * <p>
 * An adapter uses it in two places:
 * <ol>
 * <li>in <code>prepareBpmn</code>, to rewrite the identifiers of its BPMN model. Only
 * the adapter knows its model type.</li>
 * <li>at every runtime boundary, to translate identifiers on their way to the BPMS
 * (starting a workflow, correlating a message, throwing a BPMN error, querying the
 * viewer API) and on their way back (finding the
 * <code>&#64;WorkflowTask</code> handler of a delivered task).</li>
 * </ol>
 * In the modes {@link NameClashAvoidance#NONE} and
 * {@link NameClashAvoidance#BY_ADAPTER}, every <code>scoped*</code> method returns
 * its input unchanged. So an adapter may always call them.
 * <p>
 * <b>Never build the strings yourself.</b> The core owns the separator and how the
 * parts are put together, and the startup check for colliding identifiers relies on
 * it. The support itself may be missing, in a unit test or in a component built
 * without a platform around it. For that case, the STATIC methods of the same names
 * take it as their first argument. For a <code>null</code> one they answer the plain
 * identifier. So nobody has to write that rule again.
 * <p>
 * Why scoping happens at the BPMS boundary and nowhere else is decision 9 in the repository's
 * DECISIONS.md.
 */
public interface NameClashAvoidanceSupport {

  /**
   * The separator between a prefix and the identifier it scopes. Two underscores are
   * allowed in an XML {@code NCName}, so BPMN element ids may carry them (unlike e.g.
   * <code>#</code>). And they are unlikely to appear in an identifier by accident.
   */
  String SEPARATOR = "__";

  /**
   * The mode configured for the given workflow module (and, if given, workflow) of
   * the given adapter. The most specific configured value wins (workflow >
   * workflow module > adapter). Without any configuration, the adapter's own default
   * applies ({@link AdapterDeploymentService#defaultNameClashAvoidance()}).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code> to resolve the
   *          module's mode
   * @param adapterId The adapter ID
   * @return The mode, never <code>null</code>
   */
  NameClashAvoidance modeFor(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * The BPMN process ID as the BPMS knows it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The scoped ID, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedProcessId(
      String workflowModuleId,
      String bpmnProcessId,
      String adapterId);

  /**
   * An identifier scoped by the workflow module alone - message names, signal
   * names, error codes and escalation codes.
   *
   * @param workflowModuleId The workflow module ID
   * @param identifier The plain identifier (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped identifier, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedIdentifier(
      String workflowModuleId,
      String identifier,
      String adapterId);

  /**
   * A task definition, scoped by the workflow module AND by the BPMN process ID. The
   * application can switch off the second part
   * (<code>prefix-task-definitions-per-process: false</code>). Using one task
   * implementation for several processes is an anti-pattern, so scoping per process is
   * the default.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param taskDefinition The plain task definition (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped task definition, or the plain one if the mode is not
   *         {@link NameClashAvoidance#USE_PREFIX}
   */
  String scopedTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition,
      String adapterId);

  /**
   * The inverse of {@link #scopedProcessId}: the plain BPMN process ID of what the
   * BPMS reported. It removes a KNOWN prefix (never "everything up to the first
   * separator"). So an identifier without the expected prefix is returned unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The ID as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain BPMN process ID
   */
  String plainProcessId(
      String workflowModuleId,
      String scopedBpmnProcessId,
      String adapterId);

  /**
   * The inverse of {@link #scopedIdentifier}.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain identifier
   */
  String plainIdentifier(
      String workflowModuleId,
      String scopedIdentifier,
      String adapterId);

  /**
   * The inverse of {@link #scopedTaskDefinition}. The BPMN process ID is needed,
   * because task definitions are scoped per process by default. Pass the process the
   * task belongs to.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedTaskDefinition The task definition as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain task definition
   */
  String plainTaskDefinition(
      String workflowModuleId,
      String bpmnProcessId,
      String scopedTaskDefinition,
      String adapterId);

  /**
   * {@link #scopedProcessId} for the case where the support may be missing. A caller
   * which holds the support as a field needs this form. It is also the only place where
   * the rule "no scoping, plain identifier" is written down.
   * <p>
   * An adapter has the support for every adapter id the platform registered. So a unit
   * test hands in <code>null</code>, and so does a component built without a platform
   * around it. The plain identifier is not a fallback, it is the correct answer. Without
   * a mode there is nothing to prefix by, and {@link NameClashAvoidance#NONE} and
   * {@link NameClashAvoidance#BY_ADAPTER} answer the same. If every caller wrote its own
   * check, two of them would end up disagreeing, and a disagreement here is a query which
   * finds nothing.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The scoped ID, or the plain one
   */
  static String scopedProcessId(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * {@link #scopedIdentifier} for the case where the support may be missing. See
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param identifier The plain identifier (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped identifier, or the plain one
   */
  static String scopedIdentifier(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String identifier,
      final String adapterId) {

    return scoping == null
        ? identifier
        : scoping.scopedIdentifier(workflowModuleId, identifier, adapterId);

  }

  /**
   * {@link #scopedTaskDefinition} for the case where the support may be missing. See
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param taskDefinition The plain task definition (may be <code>null</code>)
   * @param adapterId The adapter ID
   * @return The scoped task definition, or the plain one
   */
  static String scopedTaskDefinition(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    return scoping == null
        ? taskDefinition
        : scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

  /**
   * {@link #plainProcessId} for the case where the support may be missing. See
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The ID as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain BPMN process ID
   */
  static String plainProcessId(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String adapterId) {

    return scoping == null
        ? scopedBpmnProcessId
        : scoping.plainProcessId(workflowModuleId, scopedBpmnProcessId, adapterId);

  }

  /**
   * {@link #plainIdentifier} for the case where the support may be missing. See
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain identifier
   */
  static String plainIdentifier(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String scopedIdentifier,
      final String adapterId) {

    return scoping == null
        ? scopedIdentifier
        : scoping.plainIdentifier(workflowModuleId, scopedIdentifier, adapterId);

  }

  /**
   * {@link #plainTaskDefinition} for the case where the support may be missing. See
   * {@link #scopedProcessId(NameClashAvoidanceSupport, String, String, String)}.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedTaskDefinition The task definition as the BPMS knows it
   * @param adapterId The adapter ID
   * @return The plain task definition
   */
  static String plainTaskDefinition(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition,
      final String adapterId) {

    return scoping == null
        ? scopedTaskDefinition
        : scoping.plainTaskDefinition(workflowModuleId, bpmnProcessId, scopedTaskDefinition, adapterId);

  }

  /**
   * Fails with a guiding message if the adapter carries configuration which only
   * {@link NameClashAvoidance#BY_ADAPTER} could use, although BY_ADAPTER applies at no
   * level of this adapter. Then the BPMS' own isolation is never asked for, so the
   * setting has no effect, and the two contradict each other. Either the setting is
   * meant, and the mode has to say so. Or the mode is meant, and the setting is dead
   * configuration. Silently ignoring it is the one outcome nobody asked for.
   * <p>
   * The ADAPTER knows WHICH setting that is, because it owns the isolation mechanism (a
   * tenant, a namespace, a database of its own; the core knows none of them). The
   * adapter decides that something is configured and hands over the property key it
   * would have to ignore. The core answers which modes apply and how to bring them
   * together. It is called while deploying (i.e. at startup), before anything reaches
   * the BPMS.
   *
   * @param adapterId The adapter ID
   * @param byAdapterOnlyPropertyKey The full property key of the configured setting
   *          which only BY_ADAPTER could use, e.g.
   *          <code>vanillabp.adapters.myengine.tenant-id</code>. The message tells the
   *          developer to remove exactly this one. <code>null</code> or blank checks
   *          nothing
   * @throws IllegalStateException Naming that property, the modes which apply and both
   *           ways out
   */
  void validateNoneNameClashStrategy(
      String adapterId,
      String byAdapterOnlyPropertyKey);

  /**
   * Fails with a guiding message if the mode for the given workflow module is
   * {@link NameClashAvoidance#BY_ADAPTER}, although the adapter's BPMS has no isolation
   * mechanism of its own. Such an adapter calls it while DEPLOYING a workflow module
   * (i.e. at startup). Otherwise every workflow module would silently be deployed into
   * one scope.
   * <p>
   * Note that {@link NameClashAvoidance#BY_ADAPTER} is the DEFAULT. So an adapter
   * without its own isolation also fails if nothing is configured at all. The developer
   * has to make a choice.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module being deployed, or <code>null</code>
   *          to check every configured level of this adapter
   * @param bpmsDescription How the BPMS is named in the message, e.g. "the
   *          Process-Engine-API"
   * @throws IllegalStateException Naming the workflow module or the configuration
   *           levels, and the alternatives
   */
  void validateNativeIsolationSupported(
      String adapterId,
      String workflowModuleId,
      String bpmsDescription);

  /**
   * Fails with a guiding message if two (workflow module, BPMN process ID) pairs of this
   * application reach the BPMS under the SAME identifier. This is the one clash which
   * loses a model. The BPMS keeps one definition under the identifier and not the other.
   * So one of the two workflow modules runs on a model nobody deployed. The adapter calls
   * it once it knows the deployed processes of the workflow module it is deploying (the
   * BPMN has to be read first).
   * <p>
   * The core REMEMBERS, per adapter id, what the earlier calls of this start passed. So
   * the SCOPE OF THE COLLECTION DOES NOT MATTER. Hand over the processes of the workflow
   * module which is being deployed, and a collision with an earlier module is found all
   * the same. Two adapter ids never see each other's processes. Two ids of one BPMS type
   * are what a migration looks like, and each one deploys its own scope. Passing the same
   * pair twice is not a collision either. One BPMN file with several processes needs
   * that.
   * <p>
   * The price of remembering: the start ends while the SECOND of the two modules
   * deploys, with the first one already in the BPMS. Why a half-deployed application is
   * the lesser evil here: see decision 41 in the repository's DECISIONS.md.
   * <p>
   * Whether two equal identifiers are a collision at all depends on the mode. Under
   * {@link NameClashAvoidance#USE_PREFIX} the core built both strings itself and needs
   * nobody else. Under {@link NameClashAvoidance#NONE} nothing is scoped, so nothing
   * separates the two sides. Under {@link NameClashAvoidance#BY_ADAPTER} the scoped form
   * is the plain one, and the BPMS is supposed to keep the modules apart. So the core
   * asks the adapter ({@link AdapterDeploymentService#ownIsolationSeparatesWorkflowModules}),
   * and it refuses only if the answer is that nothing separates them.
   *
   * @param adapterId The adapter ID
   * @param deployedProcesses The (workflow module ID, PLAIN BPMN process ID) pairs of the
   *          workflow module being deployed
   * @throws IllegalStateException Naming both workflow modules, both plain process ids, the
   *           identifier they share, the mode which produced it and the property key which
   *           sets that mode
   */
  void validateNoCollidingProcessIds(
      String adapterId,
      Collection<DeployedProcess> deployedProcesses);

  /**
   * One deployed BPMN process, used by {@link #validateNoCollidingProcessIds}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   */
  record DeployedProcess(
                         String workflowModuleId,
                         String bpmnProcessId) {
  }

  /**
   * Warns about identifiers of the given workflow module which the BPMS ALREADY held
   * before this deployment. The adapter found them by asking its BPMS. It is called while
   * deploying, so at startup. Nothing reads the result at runtime.
   * <p>
   * This never fails the start. An identifier held elsewhere is a warning, because the
   * deployment on the other side may belong to another application which runs correctly.
   * Ending this start would not help it. That rule is decision 38 in the repository's
   * DECISIONS.md.
   * <p>
   * Hand over the PLAIN identifiers. The core works out the mode and builds the scoped
   * form the BPMS sees. So the separator and the way of building stay in the one place
   * which owns them (decision 9 in the repository's DECISIONS.md). And the warning can
   * name both forms, plus the property which produced them.
   * <p>
   * An adapter which cannot ask its BPMS calls nothing at all. Silence means "not asked"
   * everywhere in this SPI, and it needs no value of its own. The core judges nothing
   * here. It reports what an adapter found, and says for each finding how sure the
   * adapter is about it.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module whose identifiers were asked about
   * @param found What the BPMS already holds, empty for a BPMS which holds none of it
   */
  void reportIdentifiersTheBpmsAlreadyHolds(
      String adapterId,
      String workflowModuleId,
      Collection<IdentifierHeldElsewhere> found);

  /**
   * One identifier of a workflow module which the BPMS already holds, used by
   * {@link #reportIdentifiersTheBpmsAlreadyHolds}.
   *
   * @param kind Which of the scoped forms this identifier is
   * @param plainIdentifier The identifier as the application knows it, without any
   *          prefix. The core builds the scoped form
   * @param bpmnProcessId The BPMN process a task definition belongs to, because task
   *          definitions are scoped per process by default. <code>null</code> for
   *          every other kind
   * @param heldBy How the BPMS names the holder. The ADAPTER writes it, because only it
   *          knows what its BPMS can say about a holder: a deployment name, a resource
   *          name, a version, a time
   * @param certainlyForeign Whether the adapter can prove that the holder is not an
   *          earlier deployment of this application. If it cannot, the warning says so,
   *          and the reader can judge the line
   */
  record IdentifierHeldElsewhere(
                                 ScopedIdentifierKind kind,
                                 String plainIdentifier,
                                 String bpmnProcessId,
                                 String heldBy,
                                 boolean certainlyForeign) {
  }

  /**
   * The kinds of identifier a workflow module scopes, and which of them a BPMS can be
   * asked about.
   * <p>
   * A query answers two of them. Camunda 7 and Camunda 8 both answer for a BPMN process
   * id and for a DMN decision id, with one search each against their repository. The
   * Process-Engine-API answers for nothing, because its API has no read method at all.
   * <p>
   * The other kinds are not kept in any index. But that does not mean they cannot be
   * known. A message name, a signal name, a BPMN error code and an escalation code live
   * inside a BPMN model, and a BPMS keeps the models and hands them back: Camunda 7 through
   * <code>RepositoryService#getBpmnModelInstance</code>, Camunda 8 through
   * <code>newProcessDefinitionGetXmlRequest</code>. Both adapters already read models
   * that way. So those names CAN be found, by reading a model instead of asking an index.
   * The complete answer is ruled out by its cost: one model has to be read per definition
   * version the BPMS holds. That number grows as long as the application is in
   * production, and decision 19 in the repository's DECISIONS.md forbids a start to do
   * that. Where the models are read anyway,
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#identifiersOfVersion}
   * answers the same question for the versions of this application's own processes.
   * <p>
   * A task definition is read from a held model like the rest. What differs is whether it
   * is scoped at all, and that depends on the BPMS. On Camunda 7 task definitions are
   * process-local: the expression is evaluated inside the process by VanillaBP's EL
   * resolver, nothing subscribes to them engine-wide. So that adapter leaves them as they
   * are on purpose, and the question does not come up there. A Camunda 8 job type is the
   * opposite case and is prefixed, because a worker subscribes to a job type across the
   * whole cluster.
   */
  enum ScopedIdentifierKind {

    /** A BPMN process id, scoped by the workflow module. */
    BPMN_PROCESS_ID,

    /** A message name, scoped by the workflow module. */
    MESSAGE_NAME,

    /** A signal name, scoped by the workflow module. */
    SIGNAL_NAME,

    /** A BPMN error code, scoped by the workflow module. */
    ERROR_CODE,

    /** An escalation code, scoped by the workflow module. */
    ESCALATION_CODE,

    /** A task definition, scoped by the workflow module and by its BPMN process. */
    TASK_DEFINITION,

    /** A DMN decision id, scoped by the workflow module. */
    DMN_DECISION_ID

  }

  /**
   * Warns if two workflow modules of THIS application declare an identifier which ends up
   * as the same scoped form, so the BPMS cannot tell the two apart. The adapter calls it
   * once per workflow module while it deploys, with what it read out of the models of that
   * module. The adapter rewrites every message name, signal name, error code, escalation
   * code and task definition while it scopes that model anyway. So it holds all of them,
   * and the question costs nothing extra. BPMN process ids have their own check,
   * {@link #validateNoCollidingProcessIds}.
   * <p>
   * A wrong task definition costs the most if the BPMS subscribes to it across the whole
   * cluster, as with a Camunda 8 job type. Two workflow modules which use the same task
   * definition under {@link NameClashAvoidance#NONE} end up with one job type. Then the
   * worker of one module fetches the jobs of the other. An adapter whose BPMS keeps task
   * definitions process-local reports none of them. Nothing here needs to know which of
   * the two kinds its BPMS is.
   * <p>
   * Under {@link NameClashAvoidance#USE_PREFIX} the two scoped forms differ by the module
   * id, so there is nothing to report. A task definition also differs by its process,
   * unless <code>prefix-task-definitions-per-process</code> is switched off. If it is
   * switched off and two processes of one module share a task definition, that is the
   * application's own choice. It stays silent, exactly like a shared message name inside
   * one module. Two modules can share a name only in two modes:
   * {@link NameClashAvoidance#NONE}, where nothing is scoped, and
   * {@link NameClashAvoidance#BY_ADAPTER} with one <code>tenant-id</code> configured for
   * the whole adapter. Then every workflow module of the application lands in the same
   * scope of the BPMS.
   * <p>
   * This warns and does not end the start, although both sides belong to this
   * application. The reasoning of decision 38 about a foreign deployment which runs
   * correctly does not apply here. But there are two reasons of its own. First, nothing
   * is overwritten. Two BPMN processes under one id make the BPMS keep one definition and
   * lose the other. Here both modules keep their own models, and only the runtime meaning
   * of the name becomes ambiguous. An application may have arranged that on purpose: one
   * module broadcasting a signal which another one catches is a design, not a defect.
   * Second, an application which ran like this yesterday must not be stopped by an
   * upgrade. But this must not stay silent either, because nothing else tells a developer
   * that a message meant for one module can reach a workflow of the other.
   * <p>
   * If several BPMN processes of ONE workflow module share a name, that is ordinary
   * VanillaBP and is never reported. The scope is the module.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module whose models were read
   * @param declared The identifiers those models declare, plain and without duplicates
   */
  void reportIdentifiersTheModelsDeclare(
      String adapterId,
      String workflowModuleId,
      Collection<ModelIdentifier> declared);

  /**
   * Warns if ONE version which a BPMS still holds declares an identifier which the current
   * deployment of ANOTHER workflow module uses. A workflow module deployed years ago leaves
   * this name clash behind. The CORE calls it while it checks the versions a BPMS still
   * holds, so an adapter does not call this. The adapter answers
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#identifiersOfVersion},
   * read from the model the BPMS hands back.
   * <p>
   * If the BPMS subscribes to task definitions, a task definition of a held version
   * matters as much as the rest. A job type of a version on which workflows still run is
   * live. If the worker of another workflow module fetches those jobs, that is the same
   * defect as at deployment time.
   * <p>
   * Most of what arrives here is no finding. If a held version of the same workflow module
   * declares a name which its current model declares as well, that is continuity. Several
   * processes of one module sharing a name is ordinary. The finding is a held version of
   * one workflow module with a name which the current deployment of a different module
   * scopes to the same form.
   * <p>
   * It is always a warning, never a refusal. The checks at deployment time do not have
   * this reason: nobody can change the held model any more, and workflows may still run on
   * it, and run correctly. The message says how many workflows that are, because the
   * number decides how urgent the line is.
   * <p>
   * The limit of this check is which models get read at all. The core asks the catalog of
   * a workflow module the application still deploys. A module the application dropped
   * completely has no catalog. Nothing is asked about it, and its names stay invisible.
   * <p>
   * What arrives here also answers a second question: a message name or a signal name
   * which NO model of this deployment declares any more. Workflows on that version wait
   * for something nothing sends. Renaming a message leaves this behind. The name lives in
   * a model the BPMS holds, and in no file of the application. That finding is a warning
   * too, and it says "look at it", not "write this". A rename whose old workflows were
   * finished by hand looks exactly like a rename nobody finished. It is silent for a
   * version nobody is on. It is also silent if this adapter never reported what the
   * current models declare. See decision 95 in the
   * repository's DECISIONS.md.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module of the held version
   * @param bpmnProcessId The plain BPMN process ID of the held version
   * @param version The version identifier the BPMS reported
   * @param activeWorkflows How many workflows still run on that version, or
   *          <code>null</code> if the BPMS cannot say
   * @param declared What that version's model declares, or <code>null</code> if the
   *          BPMS cannot read it
   */
  void reportIdentifiersOfHeldVersion(
      String adapterId,
      String workflowModuleId,
      String bpmnProcessId,
      String version,
      Long activeWorkflows,
      Collection<ModelIdentifier> declared);

  /**
   * One identifier a BPMN model declares, as the application knows it.
   *
   * @param kind Which of the scoped forms this identifier is
   * @param plainIdentifier The identifier without any prefix. The core builds the scoped
   *          form
   * @param bpmnProcessId The BPMN process which declares it, for a task definition,
   *          because those are scoped per process unless the application switched that
   *          off. <code>null</code> for every kind the workflow module scopes alone
   */
  record ModelIdentifier(
                         ScopedIdentifierKind kind,
                         String plainIdentifier,
                         String bpmnProcessId) {
  }

}
