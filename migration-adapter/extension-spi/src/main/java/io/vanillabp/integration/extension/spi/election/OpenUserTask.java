package io.vanillabp.integration.extension.spi.election;

/**
 * A user task of a workflow which VanillaBP handed to the application and which nobody has
 * completed or cancelled yet, as the delivery log wrote it down.
 * <p>
 * Every value comes from rows VanillaBP wrote itself. No BPMS was asked for it, so it is there
 * right after the delivery, also where a BPMS answers from a read model which has not caught up.
 * It is history, not a statement about now: the BPMS may have moved on in the meantime, for
 * example because a boundary event ended the task.
 *
 * @param adapterId The adapter which delivered the task. A workflow does not change its BPMS, so
 *          that adapter holds the task
 * @param workflowId The BPMS' own id of the workflow the AGGREGATE is, which on a BPMS with call
 *          activities is the super-parent instance. It is read from the row written when the
 *          workflow started. <code>null</code> where there is no such row: the workflow started
 *          before VanillaBP wrote one, or its retention passed
 *          (<code>vanillabp.delivery.workflow-start-retention</code>)
 * @param subWorkflowId The BPMS' own id of the instance the task runs in. For a task of the main
 *          process it is the same as <code>workflowId</code>, for a task of a called process it
 *          is the instance the call activity created. <code>null</code> where the adapter names
 *          no workflow id
 * @param bpmnProcessId The BPMN process the task belongs to. It differs from the process asked
 *          for where the task is part of a called process
 * @param userTaskId The BPMS' id of the user task, the one the application receives in a
 *          <code>&#64;TaskId</code> parameter
 * @param taskDefinition The task definition of the user task, or its BPMN element id where the
 *          model names none
 * @param bpmnElementId The <code>id</code> attribute of the BPMN element, as the modeller wrote
 *          it. <code>null</code> where the adapter does not name it
 * @param processVersion The version of the process definition of <code>bpmnProcessId</code> the
 *          task runs on, as the BPMS counts it. <code>null</code> where the adapter does not say
 */
public record OpenUserTask(
                           String adapterId,
                           String workflowId,
                           String subWorkflowId,
                           String bpmnProcessId,
                           String userTaskId,
                           String taskDefinition,
                           String bpmnElementId,
                           String processVersion) {

}
