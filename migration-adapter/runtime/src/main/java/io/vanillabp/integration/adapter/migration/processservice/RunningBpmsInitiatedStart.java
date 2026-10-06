package io.vanillabp.integration.adapter.migration.processservice;

/**
 * The <code>&#64;WorkflowStartedByBpms</code> method running on this thread, if any, named by
 * the workflow module and the BPMN process whose start it builds.
 * <p>
 * Such a method runs before the workflow has an aggregate, and on Camunda 8 before VanillaBP wrote
 * down which BPMS holds the workflow. A report of a changed aggregate from inside it would ask a
 * BPMS about a workflow that BPMS does not show yet, and it reports nothing the start does not
 * carry anyway: the start hands the aggregate's values to the BPMS when it ends. So a report about
 * the aggregate the method is building is skipped, see
 * {@link MigrationProcessService#isInsideTheStartOf(Object)}.
 * <p>
 * Held per thread because the method runs on the thread of the notification which started it,
 * and nothing else is told about that method. Starts may nest, a start whose method starts
 * another workflow which the BPMS reports on the same thread, so closing one gives the previous
 * one back.
 */
public final class RunningBpmsInitiatedStart implements AutoCloseable {

  private static final ThreadLocal<RunningBpmsInitiatedStart> CURRENT = new ThreadLocal<>();

  private final String workflowModuleId;

  private final String bpmnProcessId;

  private final RunningBpmsInitiatedStart previous;

  private RunningBpmsInitiatedStart(
      final String workflowModuleId,
      final String bpmnProcessId,
      final RunningBpmsInitiatedStart previous) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.previous = previous;

  }

  /**
   * Marks the thread as running the <code>&#64;WorkflowStartedByBpms</code> method of the given
   * BPMN process, until the result is closed.
   *
   * @param workflowModuleId The workflow module of the started workflow
   * @param bpmnProcessId The BPMN process of the started workflow
   * @return What to close once the method returned
   */
  public static RunningBpmsInitiatedStart of(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var running = new RunningBpmsInitiatedStart(workflowModuleId, bpmnProcessId, CURRENT.get());
    CURRENT.set(running);
    return running;

  }

  /**
   * Tells whether this thread runs the <code>&#64;WorkflowStartedByBpms</code> method of the
   * given BPMN process right now.
   *
   * @param workflowModuleId The workflow module to ask about
   * @param bpmnProcessId The BPMN process to ask about
   * @return Whether the innermost running start is one of that process
   */
  public static boolean isRunningFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var running = CURRENT.get();
    return (running != null) && running.workflowModuleId.equals(workflowModuleId) && running.bpmnProcessId
        .equals(bpmnProcessId);

  }

  /**
   * The innermost start running on this thread.
   *
   * @return The start, or empty where no <code>&#64;WorkflowStartedByBpms</code> method runs
   */
  public static java.util.Optional<RunningBpmsInitiatedStart> current() {

    return java.util.Optional.ofNullable(CURRENT.get());

  }

  /**
   * The workflow module of the workflow being started.
   *
   * @return The workflow module id
   */
  public String workflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The BPMN process of the workflow being started.
   *
   * @return The BPMN process id
   */
  public String bpmnProcessId() {

    return bpmnProcessId;

  }

  @Override
  public void close() {

    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }

  }

}
