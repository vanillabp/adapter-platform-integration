package io.vanillabp.integration.adapter.spi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * An implementation is responsible for reading the BPMN and transforming it into the model type.
 * Additionally, the final model will be deployed to the target BPMS.
 * The implementation has to be provided by the platform integration adapter.
 * <p>
 * Conceptually, an adapter is &quot;the wiring service with deployment&quot;: it inherits
 * preparing/wiring and starting/stopping of workflow processing from
 * {@link ExtensionWiringService} and adds reading and deploying of BPMS resources.
 * <p>
 * <i>Hint:</i> The {@link PC} is an object holding all information needed by the adapter to deploy the resources.
 * <p>
 * <i>Note:</i> There is deliberately no DMN model type parameter (yet): DMN support will be
 * added to this interface once it is designed.
 *
 * @param <BPMN> The BPMN model type
 * @param <PC> The context to store all information needed by the adapter for wiring and deploying BPMN
 */
public interface AdapterDeploymentService<BPMN, PC> extends ExtensionWiringService<BPMN, PC> {

  /**
   * @return The ID of the adapter implementing this interface
   */
  String getAdapterId();

  /**
   * @return The type of the adapter implementing this interface
   */
  String getAdapterType();

  /**
   * What this adapter can say about the BPMS it talks to right now, asked by the
   * platform integration whenever a health endpoint is called (Spring Boot Actuator's
   * health, Quarkus' readiness). The typical implementation is the cheapest question
   * the BPMS answers - Camunda 8 asks the cluster for its topology, which is what
   * Camunda's own starter does.
   * <p>
   * The default is <code>null</code>: an adapter which has nothing to check is
   * ABSENT from the endpoint rather than reported as healthy, and an adapter written
   * before this existed keeps working unchanged.
   * <p>
   * Two rules of the contract are worth repeating here, because they are easy to get
   * wrong (see {@link AdapterHealth}):
   * <ul>
   * <li>an adapter whose connection is not configured yet answers
   * {@link AdapterHealth.Status#UNKNOWN}, never {@link AdapterHealth.Status#DOWN} -
   * the application booted with a guiding warning on purpose;</li>
   * <li>the implementation must RETURN rather than throw, and it must come back
   * within a bounded time - the platform calls it on the thread serving the health
   * request. A failure to reach the BPMS is a {@link AdapterHealth.Status#DOWN} with
   * the reason in the description, not an exception (the core turns a thrown one into
   * {@link AdapterHealth.Status#DOWN} as a backstop).</li>
   * </ul>
   *
   * @return What the adapter found, or <code>null</code> to contribute nothing
   */
  default AdapterHealth checkHealth() {

    return null;

  }

  /**
   * Reads the given BPMN input stream and transforms it into the model type.
   *
   * @param workflowModuleId The workflow module ID
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmn The BPMN input stream. It is owned and closed by the deployment
   *        pipeline - implementations must NOT close it.
   * @param isVanillaBpBpmn Whether the input stream is VanillaBP or specific to the adapter's BPMS
   * @return The models of the executable processes found in the BPMN (key is the process ID, value is the model)
   * @throws BpmnParseException If the parsing fails
   */
  List<Map.Entry<String, BPMN>> readBpmn(
      String workflowModuleId,
      String filename,
      InputStream bpmn,
      boolean isVanillaBpBpmn) throws BpmnParseException;

  /**
   * Prepares the given model according to the feature of the adapter (e.g. setting defaults, etc.).
   *
   * @param workflowModuleId The workflow module ID
   * @param existingContext The existing context (usually from a previous BPMN) or null for the first BPMN
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmnProcessId The BPMN process ID
   * @param model The model
   * @return The context passed to startProcessing (usually used to collect wiring
   *         information); must NEVER be null - it is threaded through the whole
   *         deployment pipeline
   */
  PC prepareBpmn(
      String workflowModuleId,
      PC existingContext,
      String filename,
      String bpmnProcessId,
      BPMN model);

  /**
   * Deploys the resources (process, decision matrix) to the target BPMS.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context specific to the BPMS; never
   *        null - modules without any executable BPMN process are skipped by the
   *        deployment pipeline (with a warning) instead of being deployed
   * @throws IllegalStateException If the deployment fails
   */
  void deployResources(
      String workflowModuleId,
      PC bpmsProcessingContext) throws IllegalStateException;


  /**
   * Validates that several configured adapter ids of THIS adapter type address
   * DIFFERENT systems (story 34). Two instances of one BPMS type only make sense
   * if they do - and whether two configurations differ is BPMS knowledge, so the
   * decision belongs to the adapter:
   * <ul>
   * <li>an embedded engine: different databases/schemas (e.g. Camunda 7's
   * <code>data-source-name</code> or <code>table-prefix</code>),</li>
   * <li>a remote BPMS: different endpoints or different credentials addressing
   * the same endpoint (e.g. Camunda 8's addresses respectively the combination of
   * cluster and client id).</li>
   * </ul>
   * Called ONCE per adapter type at startup (before any deployment) on the first
   * deployment service of that type, with the ids in the order they are prioritized,
   * and only if more than one id of the type is configured - see
   * {@code DeploymentServiceTest$DistinctAdapterInstancesTests}, which is what holds
   * that. Ids which cannot be told apart must fail the boot with a guiding message
   * naming the property which makes them distinct.
   * <p>
   * The default does nothing - an adapter whose instances cannot be compared (the
   * connection is provided by the application) should say so in its documentation
   * rather than pretend.
   *
   * @param adapterIdsOfThisType The configured adapter ids of this adapter's type,
   *          in the order they are prioritized
   */
  default void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

  }

  /**
   * The {@link NameClashAvoidance} mode which applies to this adapter as long as the
   * application configures none at any level. {@link NameClashAvoidance#BY_ADAPTER} -
   * version 1's behavior, which every adapter keeps, so an application upgrading
   * without touching its configuration keeps addressing the workflows it started
   * before (Camunda 7 and Camunda 8 both deployed a tenant named after the workflow
   * module in version 1). Overriding it is a last resort rather than a taste: an
   * application which never configured the mode would silently deploy under other
   * identifiers than before, and its running workflows would not be found.
   * <p>
   * What an adapter DOES do is say what its BPMS needs for the mode. The
   * Process-Engine-API has no isolation of its own and refuses
   * {@link NameClashAvoidance#BY_ADAPTER} while deploying; a Camunda 8 cluster
   * without multi-tenancy rejects a tenant id, and the adapter turns that into a boot
   * failure naming {@link NameClashAvoidance#USE_PREFIX} and
   * {@link NameClashAvoidance#NONE} as the ways out.
   * <p>
   * Whichever mode an adapter picks, the core keeps the choice visible:
   * {@link NameClashAvoidance#NONE} is reported at startup by
   * {@link #warnAboutUnscopedIdentifiers(String, boolean)}.
   *
   * @return The mode applying without configuration, never <code>null</code>
   */
  default NameClashAvoidance defaultNameClashAvoidance() {

    return NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Reports that a workflow module reaches this adapter's BPMS with
   * {@link NameClashAvoidance#NONE}, so nothing keeps its identifiers apart from
   * those of the other workflow modules. Called by the core once per workflow module
   * and adapter id as soon as the mode is resolved (i.e. while deploying, at
   * startup), no matter whether the mode was configured or is the adapter's
   * {@link #defaultNameClashAvoidance() default}.
   * <p>
   * The message belongs to the ADAPTER because the alternatives do: Camunda 8 can
   * prefix, use tenants (on a cluster with multi-tenancy enabled) or give a workflow
   * module a cluster of its own, whereas Camunda 7 can prefix, use tenants or run a
   * separate engine per module (<code>data-source-name</code>,
   * <code>table-prefix</code>). The default names what every BPMS can offer -
   * adapters override it to name their own options.
   *
   * @param workflowModuleId The workflow module whose identifiers are not scoped
   * @param fromDefault Whether the mode is this adapter's default (nothing is
   *          configured) rather than a configured value
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
