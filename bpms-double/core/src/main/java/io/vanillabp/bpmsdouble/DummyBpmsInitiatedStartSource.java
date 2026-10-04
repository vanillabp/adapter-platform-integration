package io.vanillabp.bpmsdouble;

import java.util.Collection;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;

/**
 * Optional hook of the dummy adapter used by integration tests to supply the start
 * events a BPMS would fire on its own (timer, signal, conditional): the dummy
 * adapter has no real BPMN model, so a bean of this type stands in for what a real
 * adapter reads during <code>wireBpmn</code>. If a bean is present, the dummy
 * adapter reports the start events to the core (which validates the application's
 * <code>&#64;WorkflowStartedByBpms</code> methods against them); without a bean
 * nothing is reported.
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
   * would read them from the BPMN model. The dummy reports them to the core, which then
   * checks <code>ProcessService#startWorkflowByMessage</code> against them.
   * <p>
   * The default answers <code>null</code>, and the dummy then reports nothing. That is the shape
   * of an adapter which cannot name the messages of its model.
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
