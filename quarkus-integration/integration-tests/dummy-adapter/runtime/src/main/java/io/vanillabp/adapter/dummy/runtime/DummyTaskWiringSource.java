package io.vanillabp.adapter.dummy.runtime;

import java.util.Collection;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * Optional hook of the dummy adapter used by integration tests to supply the "BPMN
 * tasks" of a process: the dummy adapter has no real BPMN model, so a bean of this
 * type stands in for what a real adapter reads from the BPMN during
 * <code>wireBpmn</code>. If a bean is present, the dummy adapter validates the task
 * wiring against the supplied tasks (via the core's
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker});
 * without a bean no wiring validation happens.
 */
@FunctionalInterface
public interface DummyTaskWiringSource {

  /**
   * The tasks of the given BPMN process as a real adapter would read them from the
   * BPMN model.
   *
   * @param adapterId The adapter ID performing the wiring
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The tasks to be wired
   */
  Collection<BpmnTaskSpec> tasksOf(
      String adapterId,
      String workflowModuleId,
      String bpmnProcessId);

}
