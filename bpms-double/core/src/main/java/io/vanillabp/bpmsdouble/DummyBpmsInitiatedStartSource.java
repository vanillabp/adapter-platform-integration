package io.vanillabp.bpmsdouble;

import java.util.Collection;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;

/**
 * Optional hook of the dummy adapter. Integration tests use it to supply the start
 * events a BPMS would fire on its own (timer, signal, conditional). The dummy adapter has
 * no real BPMN model. So a bean of this type stands in for what a real adapter reads in
 * <code>wireBpmn</code>. If a bean is present, the dummy adapter reports the start
 * events to the core. The core checks the application's
 * <code>&#64;WorkflowStartedByBpms</code> methods against them. Without a bean nothing
 * is reported.
 */
@FunctionalInterface
public interface DummyBpmsInitiatedStartSource {

  /**
   * The BPMS-initiated start events of the given BPMN process as a real adapter
   * would read them from the BPMN model.
   *
   * @param adapterId The adapter ID performing the wiring
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The start events to be reported
   */
  Collection<BpmsInitiatedStartSpec> startEventsOf(
      String adapterId,
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The plain names of the messages which start the given BPMN process, as a real adapter
   * would read them from the BPMN model. The double reports them to the core. The core
   * then checks <code>ProcessService#startWorkflowByMessage</code> against them.
   * <p>
   * The default answers <code>null</code>, and the double then reports nothing. An adapter
   * which cannot name the messages of its model behaves the same.
   *
   * @param adapterId The adapter ID performing the wiring
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The message names to be reported, or <code>null</code> to report nothing
   */
  default Collection<String> startMessagesOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return null;

  }

}
