package io.vanillabp.integration.adapter.spi;

import java.util.Map;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;

/**
 * What phase two of an operation is given. It is phase one's request with the
 * workflow-aggregate replaced by its ID: phase two runs after the caller's transaction
 * committed, on the outbox dispatcher's thread, so the aggregate an adapter needs for
 * what it sends to the BPMS is loaded there and then.
 * <p>
 * Phase two acts, at-least-once - see the contract on
 * {@link PhaseOperationHandler#phaseTwo(PhaseTwoRequest)}.
 *
 * @param <A> The workflow-aggregate type
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param aggregatePersistence The persistence of the workflow-aggregate
 * @param workflowAggregateId The ID of the workflow aggregate in its own type,
 *        <code>null</code> for an operation which is not about one workflow (a
 *        broadcast signal)
 * @param args The operation's arguments, read through the accessors below rather than
 *        by key
 */
public record PhaseTwoRequest<A>(
                                 String workflowModuleId,
                                 String bpmnProcessId,
                                 AggregatePersistenceAware<A> aggregatePersistence,
                                 Object workflowAggregateId,
                                 Map<String, String> args) {

  public PhaseTwoRequest {
    args = args == null ? Map.of() : Map.copyOf(args);
  }

  /**
   * @return The ID of the task the operation is about, or <code>null</code>, see
   *         {@link PhaseOneRequest#taskId()}
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
   * @return The correlation id of a correlation or <code>null</code>
   */
  public String correlationId() {

    return args.get(PhaseTwoCall.ARG_CORRELATION_ID);

  }

  /**
   * @return The PLAIN BPMN signal name of a broadcast
   */
  public String signalName() {

    return args.get(PhaseTwoCall.ARG_SIGNAL_NAME);

  }

  /**
   * What the BPMS called the element instance the operation was planned in, or
   * <code>null</code> where it was planned outside any (a REST endpoint) respectively
   * by an adapter which does not name its activations. It travels only for operations
   * which say so ({@code PhaseOperation#carriesActivation()}).
   * <p>
   * A BPMS which deduplicates in a net of its own - Camunda 8 does, by the message id
   * the adapter derives - needs the same distinction VanillaBP makes on its own side:
   * three elements of a multi-instance call activity are three operations for the
   * outbox and would be ONE message for such a cluster, because a called process is a
   * secondary workflow of the same aggregate and everything else about the three
   * correlations is equal. An adapter with such a net puts this value into whatever it
   * derives its own key from; an adapter without one ignores it.
   *
   * @return The activation or <code>null</code>
   */
  public String activationId() {

    return args.get(PhaseTwoCall.ARG_ACTIVATION_ID);

  }

}
