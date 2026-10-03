package io.vanillabp.integration.adapter.spi;

import java.util.Map;

import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
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
 * @param workflowStartReport Where phase two of a START says which workflow the BPMS created,
 *        reported through {@link #reportStartedWorkflow(String)} rather than read from here.
 *        {@link WorkflowStartReport#NOBODY_LISTENS} for every other operation
 */
public record PhaseTwoRequest<A>(
                                 String workflowModuleId,
                                 String bpmnProcessId,
                                 AggregatePersistenceAware<A> aggregatePersistence,
                                 Object workflowAggregateId,
                                 Map<String, String> args,
                                 WorkflowStartReport workflowStartReport) {

  /**
   * Copies the arguments, so what a handler reads cannot be changed by whoever built the
   * request, and reads a <code>null</code> map as an empty one. A handler may therefore
   * ask for any argument and gets <code>null</code> for one its operation does not carry.
   * A request without a report sink gets the one which drops what it is told.
   */
  public PhaseTwoRequest {
    args = args == null ? Map.of() : Map.copyOf(args);
    workflowStartReport = workflowStartReport == null
        ? WorkflowStartReport.NOBODY_LISTENS
        : workflowStartReport;
  }

  /**
   * A request nobody listens to the start of, which is what every caller built before the
   * report existed. The sink stands LAST for that reason, the way a component appended to
   * {@code io.vanillabp.integration.spi.TaskDelivery} did.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate in its own type
   * @param args The operation's arguments
   */
  public PhaseTwoRequest(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final Map<String, String> args) {

    this(
        workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregateId, args, WorkflowStartReport.NOBODY_LISTENS);

  }

  /**
   * Says which workflow of the BPMS the start just created, so VanillaBP can answer later which
   * workflow this aggregate belongs to without asking any BPMS. Called by the handler of a
   * START operation, once, with the id of the instance the aggregate IS (on a BPMS with call
   * activities the super-parent). Every other handler leaves it alone, and so does an adapter
   * which has nothing to report: the call is then simply not made.
   *
   * @param workflowId The BPMS' own id of the started workflow, <code>null</code> where the
   *          adapter names none
   */
  public void reportStartedWorkflow(
      final String workflowId) {

    workflowStartReport.startedWorkflow(workflowId);

  }

  /**
   * Which task the operation addresses - the value phase one was given, which the outbox
   * persisted in between.
   *
   * @return The ID of the task the operation is about, or <code>null</code>, see
   *         {@link PhaseOneRequest#taskId()}
   */
  public String taskId() {

    return args.get(PhaseTwoCall.ARG_TASK_ID);

  }

  /**
   * How a cancellation ends the task, see {@link PhaseOneRequest#bpmnErrorCode()}.
   *
   * @return The error code a cancellation wants BPMN error boundary events to catch
   */
  public String bpmnErrorCode() {

    return args.get(PhaseTwoCall.ARG_BPMN_ERROR_CODE);

  }

  /**
   * Which message the operation sends, PLAIN as the model carries it - the adapter scopes
   * it, see {@link PhaseOneRequest#messageName()}.
   *
   * @return The BPMN message name of a correlation or of a message start event
   */
  public String messageName() {

    return args.get(PhaseTwoCall.ARG_MESSAGE_NAME);

  }

  /**
   * Which of several waiting occurrences of that message is meant, see
   * {@link PhaseOneRequest#correlationId()}.
   *
   * @return The correlation id of a correlation or <code>null</code>
   */
  public String correlationId() {

    return args.get(PhaseTwoCall.ARG_CORRELATION_ID);

  }

  /**
   * Which signal is broadcast. A broadcast is about no single workflow, so
   * {@link #workflowAggregateId()} is <code>null</code> next to it.
   *
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
