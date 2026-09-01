package io.vanillabp.integration.adapter.spi;

import java.util.Map;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;

/**
 * What phase one of an operation is given: the workflow it is about, the aggregate as
 * the application just saved it, and the arguments of the operation.
 * <p>
 * Phase one runs INSIDE the caller's transaction and only ASKS - see the contract on
 * {@link PhaseOperationHandler#phaseOne(PhaseOneRequest)}.
 *
 * @param <A> The workflow-aggregate type
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param aggregatePersistence The persistence of the workflow-aggregate
 * @param workflowAggregate The workflow-aggregate, <code>null</code> for an operation
 *        which is not about one workflow (a broadcast signal)
 * @param args The operation's arguments, read through the accessors below rather than
 *        by key
 */
public record PhaseOneRequest<A>(
                                 String workflowModuleId,
                                 String bpmnProcessId,
                                 AggregatePersistenceAware<A> aggregatePersistence,
                                 A workflowAggregate,
                                 Map<String, String> args) {

  public PhaseOneRequest {
    args = args == null ? Map.of() : Map.copyOf(args);
  }

  /**
   * @return The ID of the task the operation is about, or <code>null</code> where the
   *         operation is about none. An aggregate push carries it optionally: with a
   *         task ID the values belong to that task's scope, without it to the
   *         workflow's global one
   */
  public String taskId() {

    return args.get(PhaseTwoCall.ARG_TASK_ID);

  }

  /**
   * @return The error code a cancellation wants BPMN error boundary events to catch
   */
  public String bpmnErrorCode() {

    return args.get(PhaseTwoCall.ARG_BPMN_ERROR_CODE);

  }

  /**
   * @return The BPMN message name of a correlation or of a message start event
   */
  public String messageName() {

    return args.get(PhaseTwoCall.ARG_MESSAGE_NAME);

  }

  /**
   * @return The correlation id of a correlation or <code>null</code>. Adapters use the
   *         aggregate ID as the technical correlation key; this one additionally
   *         disambiguates BETWEEN waiting occurrences of the same message
   */
  public String correlationId() {

    return args.get(PhaseTwoCall.ARG_CORRELATION_ID);

  }

  /**
   * @return The PLAIN BPMN signal name of a broadcast - scoping identifiers is the
   *         adapter's business, see {@link NameClashAvoidanceSupport}
   */
  public String signalName() {

    return args.get(PhaseTwoCall.ARG_SIGNAL_NAME);

  }

}
