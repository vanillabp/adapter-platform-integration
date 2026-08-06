package io.vanillabp.integration.adapter.spi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

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
   * deployment service of that type, and only if more than one id of the type is
   * configured. Ids which cannot be told apart must fail the boot with a guiding
   * message naming the property which makes them distinct.
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

}
