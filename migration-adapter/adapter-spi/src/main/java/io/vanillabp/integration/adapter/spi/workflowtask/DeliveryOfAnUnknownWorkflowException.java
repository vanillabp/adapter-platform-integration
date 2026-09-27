package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * The refusal of a task delivery whose workflow aggregate is not stored in this
 * application. The core words it, an adapter carries it out: the delivery is answered as a
 * failure, and the message below is what the BPMS shows to whoever looks at it.
 * <p>
 * The situation this is written for is two applications sharing one BPMS. A task of a
 * workflow the OTHER application owns reaches this one, because both deploy a BPMN process
 * of the same name and a worker takes whatever its subscription matches. Nothing about that
 * is a defect of the application which received the job, which is why the message does not
 * read as one.
 * <p>
 * <b>The two situations cannot be told apart.</b> A workflow aggregate this application
 * never had and one it had and deleted leave exactly the same trace, which is none: the
 * delivery records of a workflow are released when it ends and the very first task of a
 * workflow has none to begin with, and the hints of the workflow-adapter cache are held in
 * memory for a while and are gone after a restart. An absent record therefore proves
 * nothing, so the message names both situations and accuses nobody of either.
 * <p>
 * The refusal is counted as <code>vanillabp.task.deliveries.unknown.workflow</code>, so the
 * question whether this happens often has an answer the second time somebody asks it. It
 * deliberately writes no delivery record: the record would be written by the
 * application which wrongly received the task, while whoever investigates reads the records
 * of the application which owns the workflow, and there it would be missing.
 */
public class DeliveryOfAnUnknownWorkflowException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * The adapter whose worker was handed this task. An application may run two adapters of the
   * same BPMS, so this is what says on which of them the refusal happened.
   */
  private final String adapterId;

  /**
   * The workflow module the BPMN process belongs to. Two modules may carry the same BPMN
   * process id, so an adapter reporting the process alone would name the wrong one.
   */
  private final String workflowModuleId;

  /**
   * The BPMN process the refused task belongs to, in the plain spelling the application uses.
   * Whatever the adapter prefixes it with on its way to the BPMS is not part of it.
   */
  private final String bpmnProcessId;

  /**
   * The task definition the delivery was routed by. It is what tells one refused task of a
   * process from another, which is what a count per task needs.
   */
  private final String taskDefinition;

  /**
   * The id the BPMS named the workflow aggregate by, serialized as it arrived. No workflow
   * aggregate of it is stored in this application, which is the whole finding.
   */
  private final String workflowAggregateId;

  /**
   * The BPMS' own id of the workflow, or <code>null</code> where the delivery carried none.
   * It is the value somebody searches the other application by, so an adapter which writes
   * the refusal anywhere writes this with it.
   */
  private final String workflowId;

  /**
   * Refuses one task delivery and words the refusal.
   *
   * @param adapterId The id of the adapter which delivered the task
   * @param workflowModuleId The workflow module the BPMN process belongs to
   * @param bpmnProcessId The BPMN process the task belongs to, as the application knows it
   * @param taskDefinition The task definition delivered
   * @param workflowAggregateClassName The class of the workflow aggregate this BPMN process
   *          works on
   * @param workflowAggregateId The ID of the workflow aggregate the BPMS named, serialized
   *          as it arrived
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code> where the
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

    super(
        message(
            adapterId,
            workflowModuleId,
            bpmnProcessId,
            taskDefinition,
            workflowAggregateClassName,
            workflowAggregateId,
            workflowId));
    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.taskDefinition = taskDefinition;
    this.workflowAggregateId = workflowAggregateId;
    this.workflowId = workflowId;

  }

  /**
   * What the reader of the incident, respectively of the adapter's log, gets to see.
   * <p>
   * It says what happened before it says what to do about it, and it says what to do about
   * it for both readings of what happened. The property key it names is the one which keeps
   * two applications apart on a BPMS which has no isolation of its own, and it is spelled
   * for THIS workflow module and THIS adapter, so it can be copied into the configuration
   * as it stands.
   */
  private static String message(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String workflowAggregateClassName,
      final String workflowAggregateId,
      final String workflowId) {

    return """
        This application was given a task of a workflow it does not own. The BPMS named the \
        workflow aggregate '%s' of class '%s', and no workflow aggregate of that ID is stored \
        here.
        Delivered by adapter '%s': task '%s' of BPMN process '%s', workflow module '%s'%s.
        Two situations end up here and VanillaBP cannot tell them apart:
          1. Another application shares this BPMS and owns this workflow. Its task reached this \
        application because both deploy this BPMN process under the same name and nothing keeps \
        the two apart. Look for the workflow in that application. How to separate them depends \
        on the BPMS: give each application an isolation of its own where the BPMS has one, a \
        tenant for example, or let VanillaBP prefix the identifiers of one of them with \
        'vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance: use-prefix'.
          2. This application owned the workflow and the workflow aggregate was deleted while \
        the workflow was still running. A workflow aggregate belongs to its workflow and has to \
        live as long as the workflow does.
        Nothing was processed and nothing was completed, so the BPMS still holds the task."""
        .formatted(
            workflowAggregateId,
            workflowAggregateClassName,
            adapterId,
            taskDefinition,
            bpmnProcessId,
            workflowModuleId,
            (workflowId == null) || workflowId.isBlank()
                ? ""
                : ", workflow '%s'".formatted(workflowId),
            workflowModuleId,
            adapterId);

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
   * The BPMS' own id of the workflow, which is what somebody searching the other
   * application starts from.
   *
   * @return The workflow id, or <code>null</code> where the delivery carried none
   */
  public String getWorkflowId() {

    return workflowId;

  }

}
