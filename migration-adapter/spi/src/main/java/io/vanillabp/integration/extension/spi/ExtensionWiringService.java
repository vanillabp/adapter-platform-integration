package io.vanillabp.integration.extension.spi;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;

/**
 * An implementation is responsible for preparing the BPMN and wiring it with the business code.
 * The implementation may be provided by custom VanillaBP extensions or by platform integration adapters.
 *
 * @param <BPMN> The BPMN model type
 * @param <PC> The context to store all information needed by the adapter for wiring and deploying BPMN
 * @see AdapterDeploymentService
 */
public interface ExtensionWiringService<BPMN, PC> {

  /**
   * @return The model type
   */
  Class<BPMN> getModelType();

  /**
   * @return The process context type
   */
  Class<PC> getProcessContextType();

  /**
   * @return The order of this service. To be used to define the order of wiring the model by multiple extensions
   *        using the same model type.
   */
  int getOrder();

  /**
   * Wires the given model with the business code.
   *
   * @param workflowModuleId The workflow module ID
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmnProcessId The BPMN process ID
   * @param model The model
   * @param context The context passed to startProcessing (usually used to collect wiring information)
   */
  void wireBpmn(
      String workflowModuleId,
      String filename,
      String bpmnProcessId,
      BPMN model,
      PC context);

  /**
   * Start running the workflows BPMN processes previously deployed.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context specific to the BPMS
   */
  void startWorkflowProcessing(
      String workflowModuleId,
      PC bpmsProcessingContext);

  /**
   * Stop running the workflows BPMN processes previously started. Called on graceful
   * shutdown of the application, before the platform's web or messaging
   * infrastructure is stopped. The default implementation does nothing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context specific to the BPMS
   */
  default void stopWorkflowProcessing(
      final String workflowModuleId,
      final PC bpmsProcessingContext) {
    // by default there is nothing to stop
  }

}
