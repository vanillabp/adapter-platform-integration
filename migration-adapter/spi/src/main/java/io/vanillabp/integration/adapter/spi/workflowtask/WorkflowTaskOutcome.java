package io.vanillabp.integration.adapter.spi.workflowtask;

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
 */
public record WorkflowTaskOutcome(
                                  Kind kind,
                                  String errorCode,
                                  String errorName) {

  public enum Kind {
    COMPLETED,
    COMPLETION_PENDING,
    BPMN_ERROR
  }

  public static WorkflowTaskOutcome completed() {

    return new WorkflowTaskOutcome(Kind.COMPLETED, null, null);

  }

  public static WorkflowTaskOutcome completionPending() {

    return new WorkflowTaskOutcome(Kind.COMPLETION_PENDING, null, null);

  }

  public static WorkflowTaskOutcome bpmnError(
      final String errorCode,
      final String errorName) {

    return new WorkflowTaskOutcome(Kind.BPMN_ERROR, errorCode, errorName);

  }

}
