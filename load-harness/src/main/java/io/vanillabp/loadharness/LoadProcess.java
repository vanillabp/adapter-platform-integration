package io.vanillabp.loadharness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The model of the process the harness plays. The double executes no model, so the harness
 * knows the sequence itself, and this class tells the double what the model would say.
 * <p>
 * The sequence is small on purpose, but it touches every kind of phase-two operation. A
 * workflow starts. Then it runs {@link FixedWork#serviceTasks()} service tasks, which complete
 * when their method returns. Then it runs one task which the application completes later,
 * by {@code ProcessService#completeTask}. Last, the application correlates one message, and
 * the workflow is done.
 */
public class LoadProcess implements DummyTaskWiringSource, DummyTaskAwarenessSource {

  /**
   * The id of the BPMN process. The double names a process after its file, so the load
   * application ships a file of this name.
   */
  public static final String BPMN_PROCESS_ID = "LoadProcess";

  /**
   * The task definition of every service task which completes when its method returns.
   */
  public static final String STEP = "step";

  /**
   * The task definition of the task which the application completes later.
   */
  public static final String WAIT = "wait";

  /**
   * The message the application correlates at the end of every workflow.
   */
  public static final String MESSAGE = "Proceed";

  /**
   * The BPMN id of the task which the application completes later.
   */
  public static final String WAIT_ELEMENT = "Activity_Wait";

  private final int serviceTasks;

  /**
   * Builds the model for a given number of service tasks.
   *
   * @param serviceTasks How many service tasks run before the task which the application
   *          completes later
   */
  public LoadProcess(
      final int serviceTasks) {

    this.serviceTasks = serviceTasks;

  }

  /**
   * The BPMN id of one service task.
   *
   * @param index The position of the task, starting at zero
   * @return The id of the element
   */
  public static String stepElement(
      final int index) {

    return "Activity_Step_%d".formatted(index);

  }

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (!BPMN_PROCESS_ID.equals(bpmnProcessId)) {
      return List.of();
    }
    final var tasks = new ArrayList<BpmnTaskSpec>();
    for (var index = 0; index < serviceTasks; index++) {
      tasks.add(new BpmnTaskSpec(stepElement(index), STEP));
    }
    tasks.add(new BpmnTaskSpec(WAIT_ELEMENT, WAIT));
    return tasks;

  }

  /**
   * Every workflow and task the harness asks about is active. The harness never completes a
   * task twice and never asks about a workflow which ended. So any other answer would only
   * cost the core a probe which a real BPMS would answer the same way.
   */
  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return WorkflowAwareness.ACTIVE;

  }

}
