package io.vanillabp.integration.extension.spi;

/**
 * An implementation is responsible for preparing the BPMN and wiring it with the business code.
 * The implementation may be provided by custom VanillaBP extensions or by platform integration adapters.
 * <p>
 * A BPMS adapter does not implement this interface directly: the adapter SPI extends it and adds
 * everything reading and deploying a model takes.
 *
 * @param <BPMN> The BPMN model type
 * @param <PC> The context to store all information needed by the adapter for wiring and deploying BPMN
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
   *        using the same model type. Defaults to <code>0</code>, so implementations (especially adapters)
   *        only need to implement this method if a specific order is required.
   */
  default int getOrder() {

    return 0;

  }

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
