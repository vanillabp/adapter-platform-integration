package io.vanillabp.integration.adapter.spi.workflowtask;

import java.time.Duration;

/**
 * The outcome of a <code>&#64;WorkflowTask</code> method which processed a BPMN task.
 * {@link WorkflowTaskInvoker#invokeWorkflowTask(String, String, TaskInvocationContext)}
 * returns it, and the adapter maps it to the BPMS:
 * <ul>
 * <li>{@link Kind#COMPLETED}: the method returned normally, and the task is to be
 * completed. The changes of the aggregate were committed.</li>
 * <li>{@link Kind#COMPLETION_PENDING}: the method returned normally, but it declares a
 * <code>&#64;TaskId</code> parameter. The task is completed later, asynchronously
 * (<code>ProcessService#completeTask</code>), and MUST NOT be completed now. The
 * changes of the aggregate were committed.</li>
 * <li>{@link Kind#BPMN_ERROR}: the method threw a
 * {@link io.vanillabp.spi.service.TaskException}. Complete the task with a BPMN error,
 * using {@link #errorCode()}, so the error boundary events decide the path. The
 * changes of the aggregate were committed.</li>
 * </ul>
 * If a method throws anything else, there is NO outcome. The transaction was rolled
 * back, the exception reaches the adapter, and the task is not completed. The BPMS
 * retries the way it always does.
 *
 * @param kind The outcome kind
 * @param errorCode The BPMN error code ({@link Kind#BPMN_ERROR} only)
 * @param errorName The BPMN error name ({@link Kind#BPMN_ERROR} only)
 * @param openFor How long the task has been open ({@link Kind#COMPLETION_PENDING}
 *          only, <code>null</code> otherwise and on the first delivery). The core
 *          measures it from the record it wrote when the handler ran. So an adapter
 *          learns the age of a task without a clock of its own
 * @param maxAgeExceeded Whether {@link #openFor()} passed the maximum age configured
 *          for this task (<code>vanillabp.delivery.max-task-age</code>). The core
 *          reports it once per task. Some BPMS have a better place for it than a log
 *          line. Their adapter reacts as well, see
 *          <code>async-task-max-age-action</code> of the Camunda 8 adapter
 */
public record WorkflowTaskOutcome(
                                  Kind kind,
                                  String errorCode,
                                  String errorName,
                                  Duration openFor,
                                  boolean maxAgeExceeded) {

  /**
   * What the adapter does with the delivered task. The comment of
   * {@link WorkflowTaskOutcome} says what each of them means for the aggregate the handler
   * worked on.
   */
  public enum Kind {

    /**
     * Complete the task.
     */
    COMPLETED,

    /**
     * Leave the task open. The application completes it later through
     * <code>ProcessService#completeTask</code>. If an adapter completed it now, it would take
     * it away from whoever is still working on it.
     */
    COMPLETION_PENDING,

    /**
     * Complete the task with a BPMN error, so the error boundary events of the model
     * decide where the workflow goes on. See {@link WorkflowTaskOutcome#errorCode()}.
     */
    BPMN_ERROR
  }

  /**
   * An outcome which says nothing about the age of an open task. Every outcome is like this,
   * except a repeated {@link Kind#COMPLETION_PENDING}.
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

  /**
   * The outcome of a handler which did its work and left nothing open.
   *
   * @return The outcome
   */
  public static WorkflowTaskOutcome completed() {

    return new WorkflowTaskOutcome(Kind.COMPLETED, null, null);

  }

  /**
   * The outcome of a task which the application completes later, without its age. That is
   * the first delivery of the task. It is also every repeated delivery whose record was
   * written before VanillaBP stored the moment.
   *
   * @return The outcome
   */
  public static WorkflowTaskOutcome completionPending() {

    return new WorkflowTaskOutcome(Kind.COMPLETION_PENDING, null, null);

  }

  /**
   * The outcome of a task which still waits for its asynchronous completion. It says how
   * long the task has been waiting.
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

  /**
   * The outcome of a handler which threw a
   * {@link io.vanillabp.spi.service.TaskException}. The aggregate was saved anyway, because
   * a BPMN error is a business answer and not a failure.
   *
   * @param errorCode The code the BPMN error boundary events are matched by
   * @param errorName The name of the error, which a BPMS may show next to the code. It is
   *          the code itself if the application named only one
   * @return The outcome
   */
  public static WorkflowTaskOutcome bpmnError(
      final String errorCode,
      final String errorName) {

    return new WorkflowTaskOutcome(Kind.BPMN_ERROR, errorCode, errorName);

  }

}
