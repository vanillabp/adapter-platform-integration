package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the task-processing acceptance test. It supplies the
 * tasks of 'TaskProcess' which match the handlers of {@link TaskWorkflowService}, so the
 * wiring check passes. Other processes have no tasks.
 */
@ApplicationScoped
public class TaskProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "TaskProcess".equals(bpmnProcessId)
        ? List.of(
            new BpmnTaskSpec("Activity_Process", "processTask"),
            new BpmnTaskSpec("Activity_Error", "raiseBpmnError"),
            new BpmnTaskSpec("Activity_Fail", "failTask"),
            new BpmnTaskSpec("Activity_Async", "asyncTask"),
            new BpmnTaskSpec("Activity_Bind", "bindParameters"),
            new BpmnTaskSpec("Activity_Mdc", "recordMdc"))
        : List.of();

  }

}
