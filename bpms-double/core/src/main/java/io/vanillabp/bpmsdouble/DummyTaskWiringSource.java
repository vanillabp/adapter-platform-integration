package io.vanillabp.bpmsdouble;

import java.util.Collection;
import java.util.List;

import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * Optional hook of the dummy adapter. Integration tests use it to supply the "BPMN
 * tasks" of a process. The dummy adapter has no real BPMN model. So a bean of this type
 * stands in for what a real adapter reads from the BPMN in <code>wireBpmn</code>. If a
 * bean is present, the dummy adapter checks the task wiring against the supplied tasks,
 * through the core's
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring}. Without
 * a bean the wiring is not checked.
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

  /**
   * The executable BPMN processes the given file declares, as a real adapter reads them
   * from the model. The default answers nothing. Then the dummy adapter keeps its rule of
   * one process per file, named after the file. Some tests need a file with a SECOND
   * executable process. A modeller produces one by drawing a called process next to the
   * calling one. Such a test names both processes here.
   *
   * @param adapterId The adapter ID reading the file
   * @param workflowModuleId The workflow module ID
   * @param filename The BPMN file's name
   * @return The executable BPMN process IDs of that file
   */
  default List<String> executableProcessesOf(
      final String adapterId,
      final String workflowModuleId,
      final String filename) {

    return List.of();

  }

  /**
   * The expressions of the given BPMN process, as a real adapter reads them from the model.
   * This means all of them: the plain names of variables, the paths and the calls, because
   * the core counts them. The default answers none. An adapter which cannot read its models
   * reports the same.
   *
   * @param adapterId The adapter ID reading the model
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The expressions of that process
   */
  default Collection<ModelExpression> expressionsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return List.of();

  }

}
