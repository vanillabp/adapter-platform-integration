package io.vanillabp.extension.dummy.runtime;

/**
 * Test hook of the dummy extension's {@link DummyWiringService}: beans implementing
 * this interface are notified about every wiring-pipeline call. Integration tests
 * use it to assert extension wiring and ordering relative to the adapters'
 * deployment pipeline.
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
