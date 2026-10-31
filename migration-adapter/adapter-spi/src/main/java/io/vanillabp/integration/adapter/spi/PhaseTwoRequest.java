package io.vanillabp.integration.adapter.spi;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.TaskDelivery;

/**
 * What phase two of an operation gets. It is the request of phase one, with the ID of the
 * workflow-aggregate instead of the aggregate. Phase two runs after the caller's
 * transaction has committed, on the thread of the outbox. So the aggregate an adapter
 * needs for what it sends to the BPMS is loaded there and then.
 * <p>
 * Phase two acts, at least once. See the contract on
 * {@link PhaseOperationHandler#phaseTwo(PhaseTwoRequest)}.
 *
 * @param <A> The workflow-aggregate type
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param aggregatePersistence The persistence of the workflow-aggregate
 * @param workflowAggregateId The ID of the workflow aggregate in its own type,
 *        <code>null</code> for an operation which is not about one workflow (a
 *        broadcast signal)
 * @param args The operation's arguments. Read them through the accessors below, not
 *        by key
 * @param workflowStartReport Where phase two of a START says which workflow the BPMS created.
 *        Report it through {@link #reportStartedWorkflow(String)}, do not read it from here.
 *        Phase two of an operation about a workflow, which got no {@link #workflowId()}, says
 *        here which workflow it found, through {@link #reportFoundWorkflow(String)}.
 *        {@link WorkflowStartReport#NOBODY_LISTENS} for every other operation
 * @param workflowId The BPMS' own id of the workflow the operation is about, if VanillaBP
 *        wrote down that THIS adapter started it, otherwise <code>null</code>. Only an
 *        operation about a workflow gets it. It is a hint, like the id the election hands to
 *        {@link MigratableProcessService#awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object, String)}.
 *        An adapter may
 *        address the workflow by it instead of searching for it. If the BPMS does not know
 *        that id, the adapter does what it would have done without one
 * @param taskRecord The row of the delivery log about the task the operation names
 *        ({@link #taskId()}), if that row names THIS adapter, otherwise <code>null</code>.
 *        Only an operation about a workflow which names a task gets it. That is a push of a
 *        changed aggregate into the scope of a task. The row exists only for a task the
 *        adapter left open ({@code TaskDelivery#outcome()} is <code>COMPLETION_PENDING</code>).
 *        {@code TaskDelivery#taskClosedAt()} says whether the application has closed it
 *        since. The row names the BPMS' own id of the workflow the task runs in, its element
 *        and its kind. So an adapter can address the task without searching for it. Like
 *        {@link #workflowId()} it is a hint. If the BPMS does not know what the row names, the
 *        adapter does what it would have done without one
 */
public record PhaseTwoRequest<A>(
                                 String workflowModuleId,
                                 String bpmnProcessId,
                                 AggregatePersistenceAware<A> aggregatePersistence,
                                 Object workflowAggregateId,
                                 Map<String, String> args,
                                 WorkflowStartReport workflowStartReport,
                                 String workflowId,
                                 TaskDelivery taskRecord) {

  /**
   * Copies the arguments, so whoever built the request cannot change what a handler reads.
   * A <code>null</code> map counts as an empty one. So a handler may ask for any argument,
   * and it gets <code>null</code> for one its operation does not carry. A request without a
   * report sink gets the sink which drops what it is told.
   */
  public PhaseTwoRequest {
    args = args == null ? Map.of() : Map.copyOf(args);
    workflowStartReport = workflowStartReport == null
        ? WorkflowStartReport.NOBODY_LISTENS
        : workflowStartReport;
  }

  /**
   * A request where nobody listens to the start. Every caller built this before the report
   * existed. That is why the sink stands LAST, like the component appended to
   * {@code io.vanillabp.integration.spi.TaskDelivery}.
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
        workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregateId, args, WorkflowStartReport.NOBODY_LISTENS, null, null);

  }

  /**
   * A request which knows no workflow id. Every caller built this before the id existed.
   * That is why the id stands LAST.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate in its own type
   * @param args The operation's arguments
   * @param workflowStartReport Where phase two of a START says which workflow the BPMS created
   */
  public PhaseTwoRequest(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final Map<String, String> args,
      final WorkflowStartReport workflowStartReport) {

    this(workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregateId, args, workflowStartReport, null, null);

  }

  /**
   * A request which knows no row of the task it names. Every caller built this before the row
   * was handed over. That is why the row stands LAST.
   *
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param aggregatePersistence The persistence of the workflow-aggregate
   * @param workflowAggregateId The ID of the workflow aggregate in its own type
   * @param args The operation's arguments
   * @param workflowStartReport Where phase two of a START says which workflow the BPMS created
   * @param workflowId The BPMS' own id of the workflow the operation is about
   */
  public PhaseTwoRequest(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final Map<String, String> args,
      final WorkflowStartReport workflowStartReport,
      final String workflowId) {

    this(
        workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregateId, args, workflowStartReport, workflowId, null);

  }

  /**
   * Says which workflow of the BPMS the start just created. Then VanillaBP can later answer
   * which workflow this aggregate belongs to, without asking any BPMS. The handler of a START
   * operation calls it once, with the id of the instance the aggregate IS (on a BPMS with
   * call activities, the top-most parent). No other handler calls it. An adapter which has
   * nothing to report does not call it either.
   *
   * @param workflowId The BPMS' own id of the started workflow, <code>null</code> if the
   *          adapter names none
   */
  public void reportStartedWorkflow(
      final String workflowId) {

    workflowStartReport.startedWorkflow(workflowId);

  }

  /**
   * Says which workflow of the BPMS the start just created AND on which version of its process
   * definition. Then VanillaBP can answer both later without asking any BPMS. Everything
   * {@link #reportStartedWorkflow(String)} says holds here as well. Call one of the two, not
   * both.
   * <p>
   * An adapter whose BPMS counts versions calls this one. VanillaBP knows which adapters count
   * versions, from the catalog an adapter registers while it wires its BPMN. If such an
   * adapter reports a start without a version, VanillaBP logs a WARN once per adapter and BPMN
   * process, and still writes the id down.
   *
   * @param workflowId The BPMS' own id of the started workflow, <code>null</code> if the
   *          adapter names none
   * @param processVersion The version of the process definition the workflow was started on, as
   *          the BPMS counts it. Use the same form which
   *          {@code io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getProcessVersion()}
   *          reports for a task of that workflow. <code>null</code> if the BPMS does not say
   */
  public void reportStartedWorkflow(
      final String workflowId,
      final String processVersion) {

    workflowStartReport.startedWorkflow(workflowId, processVersion);

  }

  /**
   * Says which workflow of the BPMS this operation found, where it had to search for it. Then
   * VanillaBP writes the id down, and the next operation about this workflow gets it as
   * {@link #workflowId()}. So the adapter can address the workflow by its id and does not
   * have to search again.
   * <p>
   * Call it in phase two of an operation about a workflow, and only where
   * {@link #workflowId()} is <code>null</code>. That is a workflow which VanillaBP knows no
   * id of: a workflow started by version 1, or one whose row has passed its period. Report
   * the id of the workflow this aggregate IS, as for {@link #reportStartedWorkflow(String)}.
   * On a BPMS with call activities, that is the top-most parent. An adapter which found
   * nothing, or which does not search at all, does not call it. VanillaBP only adds an id
   * where none is written down yet. It never replaces one.
   *
   * @param workflowId The BPMS' own id of the workflow the operation found, <code>null</code>
   *          if the adapter names none
   */
  public void reportFoundWorkflow(
      final String workflowId) {

    workflowStartReport.foundWorkflow(workflowId);

  }

  /**
   * Which task the operation addresses. It is the value phase one got, and the outbox stored
   * it in between.
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
   * Which message the operation sends, PLAIN as the model carries it. The adapter scopes
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
   * {@link #workflowAggregateId()} is <code>null</code> with it.
   *
   * @return The PLAIN BPMN signal name of a broadcast
   */
  public String signalName() {

    return args.get(PhaseTwoCall.ARG_SIGNAL_NAME);

  }

  /**
   * What the BPMS called the element instance the operation was planned in. It is
   * <code>null</code> if the operation was planned outside of any (e.g. in a REST endpoint),
   * or by an adapter which does not name its activations. Only operations which say so
   * carry it ({@code PhaseOperation#carriesActivation()}).
   * <p>
   * Some BPMS remove duplicates on their own. Camunda 8 does, by the message id the adapter
   * derives. Such a BPMS needs the same distinction VanillaBP makes on its own side. Three
   * elements of a multi-instance call activity are three operations for the outbox. For
   * such a cluster they would be ONE message, because a called process is a secondary
   * workflow of the same aggregate, and everything else about the three correlations is
   * equal. An adapter with such a check puts this value into whatever it derives its own
   * key from. An adapter without one ignores it.
   *
   * @return The activation or <code>null</code>
   */
  public String activationId() {

    return args.get(PhaseTwoCall.ARG_ACTIVATION_ID);

  }


  /**
   * When the operation was planned, in the transaction of the caller. Only a start and a
   * correlation carry it ({@link PhaseTwoCall#ARG_PLANNED_AT}).
   * <p>
   * An adapter which keeps a message for a while, because nothing waits for it yet, counts
   * that time from here. The moment stays the same for every attempt of the entry. So
   * repeating the attempt does not make the wait longer.
   *
   * @return The moment, or <code>null</code> where the operation carries none, or where the
   *         value cannot be read (an entry planned before the moment was recorded)
   */
  public Instant plannedAt() {

    final var plannedAt = args.get(PhaseTwoCall.ARG_PLANNED_AT);
    if (plannedAt == null) {
      return null;
    }
    try {
      return Instant.parse(plannedAt);
    } catch (final DateTimeParseException e) {
      // a value nobody can read counts as no value. The adapter then counts from the attempt
      // which is running, which is what it did before the moment was recorded
      return null;
    }

  }

}
