package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * The refusal of a task delivery whose workflow aggregate is not stored in this
 * application. The core writes the words, an adapter carries it out. The delivery is
 * answered as a failure, and the BPMS shows the message below to whoever looks at it.
 * <p>
 * This is written for two applications which share one BPMS. A task of a workflow which the
 * OTHER application owns reaches this one. That happens because both deploy a BPMN process
 * of the same name, and a worker takes whatever its subscription matches. This is not a
 * defect of the application which received the job, so the message does not read as one.
 * <p>
 * <b>The row written at the start tells the two situations apart.</b> In the database of the
 * aggregates, a workflow aggregate this application never had looks the same as one it had
 * and deleted. But the delivery log holds a row for the start of every workflow this
 * application started, and the core reads it before it refuses ({@link StartRecord}). If that
 * row names the workflow of the delivery, this application started it and the aggregate was
 * deleted. If there is no row, another application most likely owns the workflow. Sometimes
 * no such row can be expected at all, because there is no delivery log or the delivery names
 * no workflow. Then the message names both situations and blames nobody.
 * <p>
 * The refusal is counted as <code>vanillabp.task.deliveries.unknown.workflow</code>. So when
 * somebody asks a second time whether this happens often, there is an answer. It writes no
 * delivery record, on purpose. The application which wrongly received the task would write
 * the record. But whoever investigates reads the records of the application which owns the
 * workflow, and the record would be missing there.
 * <p>
 * Why the refusal stays loud is decision 99 in the repository's DECISIONS.md.
 */
public class DeliveryOfAnUnknownWorkflowException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * What the delivery log says about the start of the workflow of the refused delivery. It
   * decides which of the two situations the message names.
   */
  public enum StartRecord {

    /**
     * The delivery log holds the row written when this application started the workflow of
     * the delivery. So this application owned the workflow, and its workflow aggregate was
     * deleted while the workflow was still running.
     */
    STARTED_HERE,

    /**
     * The delivery log can be read and holds no row for the start of this workflow. So most
     * likely another application started it. A workflow started before the row existed, or
     * longer ago than the row is kept, leaves no row either, and the message says so.
     */
    NOT_STARTED_HERE,

    /**
     * Nothing can be said: there is no delivery log, it could not be read, the delivery names
     * no workflow, or the row names a different workflow of the same aggregate id. The message
     * names both situations.
     */
    NOT_KNOWN

  }

  /**
   * The adapter whose worker received this task. An application may run two adapters of the
   * same BPMS, so this says on which of them the refusal happened.
   */
  private final String adapterId;

  /**
   * The workflow module the BPMN process belongs to. Two modules may have the same BPMN
   * process id. So an adapter which reports only the process might name the wrong one.
   */
  private final String workflowModuleId;

  /**
   * The BPMN process the refused task belongs to, spelled as the application spells it. Any
   * prefix the adapter adds on the way to the BPMS is not part of it.
   */
  private final String bpmnProcessId;

  /**
   * The task definition the delivery was routed by. It tells one refused task of a process
   * from another, and a count per task needs that.
   */
  private final String taskDefinition;

  /**
   * The id by which the BPMS named the workflow aggregate, serialized as it arrived. This
   * application stores no workflow aggregate with this id. That is the whole problem.
   */
  private final String workflowAggregateId;

  /**
   * The BPMS' own id of the workflow, or <code>null</code> if the delivery carried none.
   * Somebody searches the other application by this value. So an adapter which writes the
   * refusal anywhere writes this id with it.
   */
  private final String workflowId;

  /**
   * What the delivery log said about the start of the workflow.
   */
  private final StartRecord startRecord;

  /**
   * Refuses one task delivery when nothing is known about the start of the workflow. So the
   * message names both situations.
   *
   * @param adapterId The id of the adapter which delivered the task
   * @param workflowModuleId The workflow module the BPMN process belongs to
   * @param bpmnProcessId The BPMN process the task belongs to, as the application knows it
   * @param taskDefinition The task definition delivered
   * @param workflowAggregateClassName The class of the workflow aggregate this BPMN process
   *          works on
   * @param workflowAggregateId The ID of the workflow aggregate the BPMS named, serialized
   *          as it arrived
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> if the
   *          delivery carried none
   */
  public DeliveryOfAnUnknownWorkflowException(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String workflowAggregateClassName,
      final String workflowAggregateId,
      final String workflowId) {

    this(
        adapterId, workflowModuleId, bpmnProcessId, taskDefinition, workflowAggregateClassName, workflowAggregateId, workflowId, StartRecord.NOT_KNOWN);

  }

  /**
   * Refuses one task delivery. The words follow what the delivery log said about the start of
   * the workflow.
   *
   * @param adapterId The id of the adapter which delivered the task
   * @param workflowModuleId The workflow module the BPMN process belongs to
   * @param bpmnProcessId The BPMN process the task belongs to, as the application knows it
   * @param taskDefinition The task definition delivered
   * @param workflowAggregateClassName The class of the workflow aggregate this BPMN process
   *          works on
   * @param workflowAggregateId The ID of the workflow aggregate the BPMS named, serialized
   *          as it arrived
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> if the
   *          delivery carried none
   * @param startRecord What the delivery log said about the start of the workflow
   */
  public DeliveryOfAnUnknownWorkflowException(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String workflowAggregateClassName,
      final String workflowAggregateId,
      final String workflowId,
      final StartRecord startRecord) {

    super(
        message(
            adapterId,
            workflowModuleId,
            bpmnProcessId,
            taskDefinition,
            workflowAggregateClassName,
            workflowAggregateId,
            workflowId,
            startRecord == null
                ? StartRecord.NOT_KNOWN
                : startRecord));
    this.startRecord = startRecord == null
        ? StartRecord.NOT_KNOWN
        : startRecord;
    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.taskDefinition = taskDefinition;
    this.workflowAggregateId = workflowAggregateId;
    this.workflowId = workflowId;

  }

  /**
   * What the reader of the incident, or of the adapter's log, gets to see.
   * <p>
   * It first says what happened, then what to do about it. If the delivery log cannot tell the
   * two situations apart, it says what to do for both. The property key it names keeps two
   * applications apart on a BPMS which cannot keep them apart itself. The key is spelled for
   * THIS workflow module and THIS adapter, so it can be copied into the configuration as it is.
   */
  private static String message(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String workflowAggregateClassName,
      final String workflowAggregateId,
      final String workflowId,
      final StartRecord startRecord) {

    final var opening = startRecord == StartRecord.STARTED_HERE
        ? "This application was given a task of a workflow whose workflow aggregate is gone."
        : "This application was given a task of a workflow it does not own.";
    final var facts = """
        %s The BPMS named the workflow aggregate '%s' of class '%s', and no workflow aggregate \
        of that ID is stored here.
        Delivered by adapter '%s': task '%s' of BPMN process '%s', workflow module '%s'%s.
        """
        .formatted(
            opening,
            workflowAggregateId,
            workflowAggregateClassName,
            adapterId,
            taskDefinition,
            bpmnProcessId,
            workflowModuleId,
            (workflowId == null) || workflowId.isBlank()
                ? ""
                : ", workflow '%s'".formatted(workflowId));
    final var anotherApplication = """
        Its task reached this application because both deploy this BPMN process under the same \
        name and nothing keeps the two apart. Look for the workflow in that application. How to \
        separate them depends on the BPMS: give each application an isolation of its own where \
        the BPMS has one, a tenant for example, or let VanillaBP prefix the identifiers of one of \
        them with 'vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance: use-prefix'."""
        .formatted(workflowModuleId, adapterId);
    final var deleted = """
        the workflow aggregate was deleted while the workflow was still running. A workflow \
        aggregate belongs to its workflow and has to live as long as the workflow does.""";
    final var reading = switch (startRecord) {
      case STARTED_HERE -> """
          This application started this workflow: its delivery log holds the row written at the \
          start. So %s Restore the workflow aggregate, or cancel the workflow in the BPMS if \
          nobody needs it any more."""
          .formatted(deleted);
      case NOT_STARTED_HERE -> """
          This application has no record of starting this workflow, so most likely another \
          application shares this BPMS and owns this workflow. %s
          There is one other reading. The record of a start is kept for \
          'vanillabp.delivery.workflow-start-retention' and does not exist for a workflow which \
          started before VanillaBP 2. If this workflow is that old, this application may have \
          owned it, and %s"""
          .formatted(anotherApplication, deleted);
      case NOT_KNOWN -> """
          Two situations end up here and VanillaBP cannot tell them apart:
            1. Another application shares this BPMS and owns this workflow. %s
            2. This application owned the workflow and %s"""
          .formatted(anotherApplication, deleted);
    };
    return facts
        + reading
        + "\nNothing was processed and nothing was completed, so the BPMS still holds the task.";

  }

  /**
   * What the delivery log said about the start of the workflow. The message names the likely
   * situation based on it.
   *
   * @return What the delivery log said, never <code>null</code>
   */
  public StartRecord getStartRecord() {

    return startRecord;

  }

  /**
   * The id of the adapter which delivered the task.
   *
   * @return The adapter id
   */
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * The workflow module the BPMN process belongs to.
   *
   * @return The workflow module id
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The BPMN process the refused task belongs to, as the application knows it.
   *
   * @return The BPMN process id
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * The task definition which was delivered.
   *
   * @return The task definition
   */
  public String getTaskDefinition() {

    return taskDefinition;

  }

  /**
   * The ID of the workflow aggregate the BPMS named, which this application does not have.
   *
   * @return The serialized workflow aggregate ID
   */
  public String getWorkflowAggregateId() {

    return workflowAggregateId;

  }

  /**
   * The BPMS' own id of the workflow. Somebody who searches the other application starts
   * from it.
   *
   * @return The workflow id, or <code>null</code> if the delivery carried none
   */
  public String getWorkflowId() {

    return workflowId;

  }

}
