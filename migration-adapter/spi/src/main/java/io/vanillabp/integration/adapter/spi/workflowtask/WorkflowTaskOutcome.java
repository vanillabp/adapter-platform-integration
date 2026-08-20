package io.vanillabp.integration.adapter.spi.workflowtask;

import java.time.Duration;

/**
 * The outcome of processing a BPMN task by a <code>&#64;WorkflowTask</code> method,
 * returned by
 * {@link WorkflowTaskInvoker#invokeWorkflowTask(String, String, TaskInvocationContext)}.
 * The adapter maps it to the BPMS:
 * <ul>
 * <li>{@link Kind#COMPLETED} - the method returned normally and the task is to be
 * completed; the aggregate changes were committed.</li>
 * <li>{@link Kind#COMPLETION_PENDING} - the method returned normally but declares a
 * <code>&#64;TaskId</code> parameter: the task is completed asynchronously later
 * (<code>ProcessService#completeTask</code>) and MUST NOT be completed now; the
 * aggregate changes were committed.</li>
 * <li>{@link Kind#BPMN_ERROR} - the method threw a
 * {@link io.vanillabp.spi.service.TaskException}: complete the task with a BPMN
 * error using {@link #errorCode()} so error-boundary routing applies; the aggregate
 * changes were committed.</li>
 * </ul>
 * A method throwing anything else does NOT yield an outcome: the transaction was
 * rolled back, the exception propagates to the adapter and the task is not
 * completed - the BPMS-side retry semantics apply.
 *
 * @param kind The outcome kind
 * @param errorCode The BPMN error code ({@link Kind#BPMN_ERROR} only)
 * @param errorName The BPMN error name ({@link Kind#BPMN_ERROR} only)
 * @param openFor How long the task has been open ({@link Kind#COMPLETION_PENDING}
 *          only, <code>null</code> otherwise and on the first delivery): the core
 *          measures it from the record it wrote when the handler ran, so an adapter
 *          learns the age of a task without keeping a clock of its own
 * @param maxAgeExceeded Whether {@link #openFor()} passed the maximum age configured
 *          for this task (<code>vanillabp.delivery.max-task-age</code>). The core
 *          reports it once per task; an adapter whose BPMS has somewhere better to
 *          put it than a log line reacts in addition, see
 *          <code>async-task-max-age-action</code> of the Camunda 8 adapter
 */
public record WorkflowTaskOutcome(
                                  Kind kind,
                                  String errorCode,
                                  String errorName,
                                  Duration openFor,
                                  boolean maxAgeExceeded) {

  public enum Kind {
    COMPLETED,
    COMPLETION_PENDING,
    BPMN_ERROR
  }

  /**
   * An outcome which says nothing about the age of an open task - what every outcome
   * besides a repeated {@link Kind#COMPLETION_PENDING} is.
   *
   * @param kind The outcome kind
   * @param errorCode The BPMN error code ({@link Kind#BPMN_ERROR} only)
   * @param errorName The BPMN error name ({@link Kind#BPMN_ERROR} only)
   */
  public WorkflowTaskOutcome(
      final Kind kind,
      final String errorCode,
      final String errorName) {

    this(kind, errorCode, errorName, null, false);

  }

  public static WorkflowTaskOutcome completed() {

    return new WorkflowTaskOutcome(Kind.COMPLETED, null, null);

  }

  public static WorkflowTaskOutcome completionPending() {

    return new WorkflowTaskOutcome(Kind.COMPLETION_PENDING, null, null);

  }

  /**
   * The outcome of a task which is still waiting for its asynchronous completion,
   * carrying how long it has been waiting.
   *
   * @param openFor How long the task has been open
   * @param maxAgeExceeded Whether that passed the configured maximum age
   * @return The outcome
   */
  public static WorkflowTaskOutcome completionPending(
      final Duration openFor,
      final boolean maxAgeExceeded) {

    return new WorkflowTaskOutcome(Kind.COMPLETION_PENDING, null, null, openFor, maxAgeExceeded);

  }

  public static WorkflowTaskOutcome bpmnError(
      final String errorCode,
      final String errorName) {

    return new WorkflowTaskOutcome(Kind.BPMN_ERROR, errorCode, errorName);

  }

}
