package io.vanillabp.extension.dummy.runtime;

/**
 * Test hook of the dummy extension's {@link DummyWiringService}. Beans which implement
 * this interface hear about every call of the wiring pipeline. Integration tests use it
 * to check the wiring of the extension, and its order compared to the deployment pipeline
 * of the adapters.
 */
public interface DummyExtensionListener {

  /**
   * Notified for every pipeline call of the dummy extension.
   *
   * @param method The pipeline method called (<code>wireBpmn</code>,
   *        <code>startWorkflowProcessing</code>,
   *        <code>stopWorkflowProcessing</code>)
   * @param workflowModuleId The workflow module id
   * @param detail The BPMN process id (wireBpmn) or <code>null</code> (module-level
   *        calls)
   */
  void onPipelineCall(
      String method,
      String workflowModuleId,
      String detail);

}
