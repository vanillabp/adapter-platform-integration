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
   * @param bpmn The BPMN input stream
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
   * @return The context passed to startProcessing (usually used to collect wiring information)
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
   * @param bpmsProcessingContext The processing context specific to the BPMS
   * @throws IllegalStateException If the deployment fails
   */
  void deployResources(
      String workflowModuleId,
      PC bpmsProcessingContext) throws IllegalStateException;

}
