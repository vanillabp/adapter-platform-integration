package io.vanillabp.integration.adapter.spi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * An implementation reads the BPMN and turns it into the model type. Then it deploys the
 * final model to the target BPMS.
 * Each BPMS adapter provides an implementation (for example the Camunda 8 adapter).
 * <p>
 * Think of an adapter as &quot;the wiring service with deployment&quot;. It inherits
 * preparing, wiring, starting and stopping of workflow processing from
 * {@link ExtensionWiringService}, and it adds reading and deploying of BPMS resources.
 * <p>
 * <i>Hint:</i> The {@link PC} is an object which holds everything the adapter needs to deploy
 * the resources.
 * <p>
 * <i>Note:</i> A DMN file travels as BYTES ({@link #readDmn}), not as a model type of its
 * own. No adapter has to understand a decision table. Only the decision id has to be
 * rewritten, if a workflow module is scoped by prefixes. That is the same for every BPMS,
 * and {@link DmnDecisionIds} does it.
 *
 * @param <BPMN> The BPMN model type
 * @param <PC> The context to store all information needed by the adapter for wiring and deploying BPMN
 */
public interface AdapterDeploymentService<BPMN, PC> extends ExtensionWiringService<BPMN, PC> {

  /**
   * Which configured adapter instance this deployment service belongs to. One instance is
   * built per configured adapter id. Several ids of one type are the setup for a migration.
   * The id is an identity, not a label. Everything VanillaBP writes down about a workflow
   * carries it. So renaming an id leaves that work without an owner. See decision 17 in the
   * repository's DECISIONS.md.
   *
   * @return The ID of the adapter implementing this interface
   */
  String getAdapterId();

  /**
   * Which BPMS this adapter talks to. It is a fixed name which the adapter ships, not
   * something an application configures (<code>camunda7</code>, <code>camunda8</code>).
   * The core groups the configured instances by it before it asks
   * {@link #validateDistinctAdapterInstances(List)}. At startup the core also looks up the
   * version descriptor of the adapter by this name. With it, it checks whether this adapter
   * and the platform integration on the classpath were built for each other.
   *
   * @return The type of the adapter implementing this interface
   */
  String getAdapterType();

  /**
   * What this adapter can say about the BPMS it talks to right now. The platform
   * integration asks it whenever a health endpoint is called (the health of Spring Boot
   * Actuator, the readiness of Quarkus). Usually an implementation asks the cheapest
   * question the BPMS answers. Camunda 8 asks the cluster for its topology, as Camunda's
   * own starter does.
   * <p>
   * The default is <code>null</code>. An adapter which has nothing to check is then
   * ABSENT from the endpoint, and not reported as healthy. An adapter written before this
   * method existed keeps working unchanged.
   * <p>
   * Two rules of the contract are easy to get wrong, so here they are again (see
   * {@link AdapterHealth}):
   * <ul>
   * <li>If the connection of an adapter is not configured yet, it answers
   * {@link AdapterHealth.Status#UNKNOWN}, never {@link AdapterHealth.Status#DOWN}. The
   * application started with a guiding warning on purpose.</li>
   * <li>The implementation must RETURN and not throw, and it must come back within a
   * limited time, because the platform calls it on the thread which serves the health
   * request. A failure to reach the BPMS is a {@link AdapterHealth.Status#DOWN} with the
   * reason in the description, not an exception. As a safety net, the core turns a thrown
   * exception into {@link AdapterHealth.Status#DOWN}.</li>
   * </ul>
   *
   * @return What the adapter found, or <code>null</code> to contribute nothing
   */
  default AdapterHealth checkHealth() {

    return null;

  }

  /**
   * Reads the given BPMN input stream and transforms it into the model type. This is the
   * first call of the deployment pipeline and it runs once per BPMN file of a workflow
   * module.
   * <p>
   * One file may declare several executable processes, and they all live in ONE parsed
   * model. So the usual answer is one entry per executable process, all with the same
   * model object. Both Camunda adapters return that. A process which is not executable is
   * left out of the list. An empty list is allowed. It says that the file holds nothing
   * this BPMS can run. The pipeline then warns once, names the file, and goes on with the
   * next one.
   * <p>
   * Everything which rewrites the model belongs into {@link #prepareBpmn}, not here.
   *
   * @param workflowModuleId The workflow module ID
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmn The BPMN input stream. It is owned and closed by the deployment
   *        pipeline - implementations must NOT close it.
   * @param isVanillaBpBpmn Where the file came from. <code>true</code> for
   *        <code>vanillabp.resources-location</code>, the location which is the same for
   *        every BPMS. <code>false</code> for a location configured at one adapter or
   *        derived for it, whose files are written for that BPMS. It says nothing about
   *        what is IN the file, because nothing reads the file before this call. Today no
   *        adapter acts on it, and reading the same dialect either way is correct. It is
   *        passed because only an adapter can decide what a file of the neutral location
   *        may contain
   * @return The models of the executable processes found in the BPMN (key is the process ID, value is the model)
   * @throws BpmnParseException If the parsing fails
   */
  List<Map.Entry<String, BPMN>> readBpmn(
      String workflowModuleId,
      String filename,
      InputStream bpmn,
      boolean isVanillaBpBpmn) throws BpmnParseException;

  /**
   * Prepares the given model for the features of the adapter (e.g. setting defaults).
   * This is where a model is REWRITTEN: listeners are attached, identifiers scoped, and
   * defaults of this BPMS filled in. Wiring reads the model afterwards and changes nothing.
   * <p>
   * It is called once per executable PROCESS, but all processes of one file share ONE
   * model object. Some rewrites are about the file, and scoping identifiers is one of
   * them. Such a rewrite has to run for the first process of that file, and the context
   * has to remember that it ran. Both Camunda adapters keep such a guard. If it runs per
   * process instead, the rewrites pile up: two prefixes on one identifier, two listeners
   * on one element. The deployment then looks correct until a workflow reaches that
   * element.
   *
   * @param workflowModuleId The workflow module ID
   * @param existingContext The existing context (usually from a previous BPMN) or null for the first BPMN
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmnProcessId The BPMN process ID
   * @param model The model, shared with every other executable process of the same file
   * @return The context passed to startProcessing (usually used to collect wiring
   *         information). It must NEVER be null, because it is passed through the whole
   *         deployment pipeline
   */
  PC prepareBpmn(
      String workflowModuleId,
      PC existingContext,
      String filename,
      String bpmnProcessId,
      BPMN model);

  /**
   * Takes one DMN file of the workflow module and adds it to the processing context. Then
   * {@link #deployResources(String, Object)} deploys it together with the processes of the
   * module. A decision which a business rule task calls is part of the module and travels
   * with it.
   * <p>
   * The DMN files of a module are read AFTER its BPMN files, so the context given here
   * exists. The pipeline skips a module without an executable process completely.
   * <p>
   * If the module is scoped by prefixes, not by an isolation of the BPMS, the decision ids
   * of the file have to be rewritten before it is deployed. The reference from a business
   * rule task has to be rewritten with them. {@link DmnDecisionIds} explains both halves,
   * and why only the second one is up to the adapter.
   * <p>
   * The default takes no file and says so once per file. An adapter written before this
   * method existed keeps working. And an application which brings a decision table to a
   * BPMS which cannot hold one is told about it. It is not left with a business rule task
   * for which nobody deployed anything.
   *
   * @param workflowModuleId The workflow module ID
   * @param existingContext The context the module's BPMN files produced. Never null
   * @param filename The filename of the DMN file (used for logging and error messages)
   * @param dmn The DMN input stream. The deployment pipeline owns and closes it.
   *          Implementations must NOT close it.
   * @return The context the DMN was added to. Must never be null
   */
  default PC readDmn(
      final String workflowModuleId,
      final PC existingContext,
      final String filename,
      final InputStream dmn) {

    org.slf4j.LoggerFactory
        .getLogger(AdapterDeploymentService.class)
        .warn(
            """
                The adapter '{}' does not deploy DMN files, so the decision table '{}' of workflow \
                module '{}' was NOT deployed to its BPMS - a business rule task calling it will fail \
                at runtime. To solve this either deploy the decision to that BPMS by other means, or \
                remove the file from the module's resources location if the BPMS of this adapter is \
                not meant to serve those workflows.""",
            getAdapterId(),
            filename,
            workflowModuleId);
    return existingContext;

  }

  /**
   * What this BPMS knows about the versions of a BPMN process which this application
   * DECLARES, but under which it deployed nothing. A renamed BPMN process leaves this case
   * behind. The old id stays in the BPMS, with every version ever deployed under it and
   * with the workflows still running on them. The application brings only the new name and
   * says so with <code>&#64;WorkflowService(secondaryBpmnProcesses = ...)</code>.
   * <p>
   * The core asks once per such id, after the workflow module finished deploying, because
   * only the core knows which ids an application declared. It registers the answer like a
   * catalog handed over by
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions}
   * during <code>wireBpmn</code>. So the startup check for old process versions also
   * reaches the versions of the old id. Otherwise a method which nobody kept would only
   * show up as an incident on a live workflow.
   * <p>
   * An adapter which answers <code>null</code> keeps working unchanged. That is the default,
   * and the honest answer of a BPMS which cannot be asked about the versions of a process.
   * The check then stays silent about that id, exactly as before this method existed.
   * <p>
   * The id is the PLAIN one. So an adapter scopes it the way it scopes every other id (a
   * prefix, a tenant) before it asks its BPMS. It costs one query per declared-only id and
   * per start, which is the shape decision 19 in the repository's DECISIONS.md asks for:
   * it follows the declarations of the application, not its history.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID nothing was deployed under
   * @return The versions of that process, or <code>null</code> if this BPMS cannot say
   */
  default io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog processVersionCatalogOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return null;

  }

  /**
   * Deploys the resources (process, decision matrix) to the target BPMS. It is called once
   * per workflow module, after every file of that module was read, prepared and wired.
   * <p>
   * Three calls belong at the end of it. An adapter which leaves them out compiles and
   * deploys, but then loses what they give:
   * <ul>
   * <li>{@link NameClashAvoidanceSupport#validateNoCollidingProcessIds}
   * with the PLAIN process ids of this module. It stops the start if two BPMN processes
   * would reach this BPMS under one identifier.</li>
   * <li>{@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerDeployedVersion}
   * per process, also if the BPMS deployed nothing because nothing had changed. Only the
   * adapter can find out which version a process ended up on. The core needs that border
   * between the model of this start and the older ones.</li>
   * <li>{@link NameClashAvoidanceSupport#reportIdentifiersTheModelsDeclare} with the names
   * the models of this module declare. If this BPMS keeps a searchable repository, also
   * {@link NameClashAvoidanceSupport#reportIdentifiersTheBpmsAlreadyHolds}.</li>
   * </ul>
   * <p>
   * For a failed deployment, an adapter only throws. The core decides the rest, by
   * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>: either the start ends,
   * or an adapter with lower priority fails without stopping the application.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context of the BPMS. Never null,
   *        because the deployment pipeline skips modules without any executable BPMN
   *        process (with a warning) instead of deploying them
   * @throws IllegalStateException If the deployment fails
   */
  void deployResources(
      String workflowModuleId,
      PC bpmsProcessingContext) throws IllegalStateException;


  /**
   * Checks that several configured adapter ids of THIS adapter type address DIFFERENT
   * systems. Two instances of one BPMS type only make sense if they do. Whether two
   * configurations differ needs knowledge about the BPMS, so the adapter decides:
   * <ul>
   * <li>an embedded engine: different databases or schemas (e.g. the
   * <code>data-source-name</code> or <code>table-prefix</code> of Camunda 7),</li>
   * <li>a remote BPMS: different endpoints, or different credentials for the same
   * endpoint (e.g. the addresses of Camunda 8, or the combination of cluster and client
   * id).</li>
   * </ul>
   * It is called ONCE per adapter type at startup, before any deployment, on the first
   * deployment service of that type. It gets the ids in the order of their priority. It
   * is only called if more than one id of the type is configured. See
   * {@code DeploymentServiceTest$DistinctAdapterInstancesTests}, which checks that. If
   * ids cannot be told apart, the start must fail with a guiding message which names the
   * property that makes them distinct.
   * <p>
   * The default does nothing. Some adapters cannot compare their instances, because the
   * application provides the connection. Such an adapter should say so in its
   * documentation, and not pretend.
   *
   * @param adapterIdsOfThisType The configured adapter ids of this adapter's type,
   *          in the order they are prioritized
   */
  default void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

  }

  /**
   * The {@link NameClashAvoidance} mode which applies to this adapter as long as the
   * application configures none at any level. It is {@link NameClashAvoidance#BY_ADAPTER},
   * the behaviour of version 1, and every adapter keeps it. So an application which
   * upgrades without touching its configuration still addresses the workflows it started
   * before. In version 1, Camunda 7 and Camunda 8 both deployed a tenant named after the
   * workflow module. Override it only as a last resort, not by taste. An application which
   * never configured the mode would silently deploy under other identifiers than before,
   * and its running workflows would not be found.
   * <p>
   * What an adapter DOES do is say what its BPMS needs for the mode. The
   * Process-Engine-API has no isolation of its own and refuses
   * {@link NameClashAvoidance#BY_ADAPTER} while deploying. A Camunda 8 cluster without
   * multi-tenancy rejects a tenant id. The adapter turns that into a failed start which
   * names {@link NameClashAvoidance#USE_PREFIX} and {@link NameClashAvoidance#NONE} as the
   * ways out.
   * <p>
   * Whichever mode an adapter picks, the core keeps the choice visible.
   * {@link #warnAboutUnscopedIdentifiers(String, boolean)} reports
   * {@link NameClashAvoidance#NONE} at startup.
   *
   * @return The mode applying without configuration, never <code>null</code>
   */
  default NameClashAvoidance defaultNameClashAvoidance() {

    return NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Whether the own isolation mechanism of this adapter would put the two given workflow
   * modules into DIFFERENT scopes of its BPMS. The core asks this while it checks whether
   * two BPMN processes reach the BPMS under one identifier
   * ({@link NameClashAvoidanceSupport#validateNoCollidingProcessIds}), if the mode which
   * applies is {@link NameClashAvoidance#BY_ADAPTER}. That mode leaves the identifiers
   * plain. So the core holds two equal strings and still cannot say whether they collide.
   * There the BPMS keeps the modules apart, and the mechanism is yours.
   * <p>
   * Answer about the scope you would REALLY deploy those two modules to. Never answer
   * about the configuration you happen to read. A tenant id can be set per adapter, per
   * workflow module and per workflow. So two modules can land in one tenant, although no
   * line of the application says so. Work out the scope for each of the two modules and
   * compare the results. Both Camunda adapters do that with their tenants. A module which
   * would reach the BPMS without any scope has a scope like any other, and two such
   * modules are not separated.
   * <p>
   * The default answers <code>false</code>, which means "my isolation separates nothing".
   * That is the honest answer for a BPMS without isolation of its own, such as the
   * Process-Engine-API. It is also the safe side for an adapter which has not answered the
   * question yet. The check then refuses, and does not let a collision through.
   *
   * @param oneWorkflowModuleId One of the two workflow modules
   * @param anotherWorkflowModuleId The other one
   * @return Whether the BPMS itself would keep the two apart
   */
  default boolean ownIsolationSeparatesWorkflowModules(
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    return false;

  }

  /**
   * Reports that a workflow module reaches the BPMS of this adapter with
   * {@link NameClashAvoidance#NONE}. So nothing keeps its identifiers apart from those of
   * the other workflow modules. The core calls it once per workflow module and adapter id,
   * as soon as the mode is known (i.e. while deploying, at startup). It does so whether
   * the mode was configured or is the {@link #defaultNameClashAvoidance() default} of the
   * adapter.
   * <p>
   * The message belongs to the ADAPTER, because the alternatives do. Camunda 8 can prefix,
   * use tenants (on a cluster with multi-tenancy enabled) or give a workflow module a
   * cluster of its own. Camunda 7 can prefix, use tenants or run a separate engine per
   * module (<code>data-source-name</code>, <code>table-prefix</code>). The default names
   * what every BPMS can offer. Adapters override it to name their own options.
   *
   * @param workflowModuleId The workflow module whose identifiers are not scoped
   * @param fromDefault Whether the mode is this adapter's default (nothing is
   *          configured) and not a configured value
   */
  default void warnAboutUnscopedIdentifiers(
      final String workflowModuleId,
      final boolean fromDefault) {

    org.slf4j.LoggerFactory
        .getLogger(getClass())
        .warn(
            """
                Workflow module '{}' is deployed to adapter '{}' with name-clash-avoidance 'none'{}, \
                so its BPMN process ids, message names and task definitions reach the BPMS as they \
                are. Nothing protects them: a second workflow module using the same identifier \
                addresses the same processes and tasks, and neither VanillaBP nor the BPMS can tell. \
                Keep 'none' only as long as your identifiers are unique across ALL workflow modules \
                of this application, otherwise choose:
                  vanillabp.adapters.{}.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers with the workflow module id
                  vanillabp.adapters.{}.name-clash-avoidance: by-adapter   # the BPMS' own isolation mechanism
                The same key may be set per workflow module \
                (vanillabp.workflow-modules.{}.adapters.{}.name-clash-avoidance). The mode is not a \
                runtime switch - changing it once workflows are running is a BPMS migration.""",
            workflowModuleId,
            getAdapterId(),
            fromDefault
                ? " (nothing is configured, so the adapter's default applies)"
                : "",
            getAdapterId(),
            getAdapterId(),
            workflowModuleId,
            getAdapterId());

  }

}
