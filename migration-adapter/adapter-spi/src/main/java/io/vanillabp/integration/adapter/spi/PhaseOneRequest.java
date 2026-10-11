package io.vanillabp.integration.adapter.spi;

import java.util.Map;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;

/**
 * What phase one of an operation gets: the workflow it is about, the aggregate as the
 * application just saved it, and the arguments of the operation.
 * <p>
 * Phase one runs INSIDE the caller's transaction and only ASKS. See the contract on
 * {@link PhaseOperationHandler#phaseOne(PhaseOneRequest)}.
 *
 * @param <A> The workflow-aggregate type
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param aggregatePersistence The persistence of the workflow-aggregate
 * @param workflowAggregate The workflow-aggregate, <code>null</code> for an operation
 *        which is not about one workflow (a broadcast signal)
 * @param args The operation's arguments. Read them through the accessors below, not
 *        by key
 */
public record PhaseOneRequest<A>(
                                 String workflowModuleId,
                                 String bpmnProcessId,
                                 AggregatePersistenceAware<A> aggregatePersistence,
                                 A workflowAggregate,
                                 Map<String, String> args) {

  /**
   * Copies the arguments, so whoever built the request cannot change what a handler reads.
   * A <code>null</code> map counts as an empty one. So a handler may ask for any argument,
   * and it gets <code>null</code> for one its operation does not carry.
   */
  public PhaseOneRequest {
    args = args == null ? Map.of() : Map.copyOf(args);
  }

  /**
   * Which task the operation addresses. It is the id of the BPMS which the adapter
   * reported when it delivered the task
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getTaskId()}),
   * and the application hands it back.
   *
   * @return The ID of the task the operation is about, or <code>null</code> if the
   *         operation is about no task. An aggregate push may carry it. With a task ID
   *         the values belong to the scope of that task, without it to the global
   *         scope of the workflow
   */
  public String taskId() {

    return args.get(PhaseTwoCall.ARG_TASK_ID);

  }

  /**
   * How a cancellation ends the task. Cancelling a task means ending it with a BPMN
   * error. So the code belongs to that call, and no other operation carries one.
   *
   * @return The error code a cancellation wants BPMN error boundary events to catch
   */
  public String bpmnErrorCode() {

    return args.get(PhaseTwoCall.ARG_BPMN_ERROR_CODE);

  }

  /**
   * Which message the operation sends. It is the PLAIN name as the model carries it. So an
   * adapter which scopes identifiers scopes this one before it asks its BPMS. See
   * {@link NameClashAvoidanceSupport}.
   *
   * @return The BPMN message name of a correlation or of a message start event
   */
  public String messageName() {

    return args.get(PhaseTwoCall.ARG_MESSAGE_NAME);

  }

  /**
   * Which of several waiting occurrences of that message is meant, if the application said
   * so.
   *
   * @return The correlation id of a correlation or <code>null</code>. Adapters use the
   *         aggregate ID as the technical correlation key. This id also tells apart
   *         several waiting occurrences of the same message
   */
  public String correlationId() {

    return args.get(PhaseTwoCall.ARG_CORRELATION_ID);

  }

  /**
   * Which signal is broadcast. A broadcast is the only operation which is about no single
   * workflow. So {@link #workflowAggregate()} is <code>null</code> with it.
   *
   * @return The PLAIN BPMN signal name of a broadcast. Scoping identifiers is up to the
   *         adapter, see {@link NameClashAvoidanceSupport}
   */
  public String signalName() {

    return args.get(PhaseTwoCall.ARG_SIGNAL_NAME);

  }

}
