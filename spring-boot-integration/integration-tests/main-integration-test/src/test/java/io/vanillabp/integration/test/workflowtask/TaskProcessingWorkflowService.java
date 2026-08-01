package io.vanillabp.integration.test.workflowtask;

import org.springframework.beans.factory.annotation.Autowired;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the task-processing acceptance test: one
 * <code>&#64;WorkflowTask</code> method per user-visible variation (happy path,
 * BPMN error, failure, asynchronous task, parameter binding). See
 * {@link TaskProcessingAggregate} for why this lives in its own package.
 */
@WorkflowService(
    workflowAggregateClass = TaskProcessingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskProcess"))
public class TaskProcessingWorkflowService {

  @Autowired
  private ProcessService<TaskProcessingAggregate> processService;

  @WorkflowTask
  public void processTask(
      final TaskProcessingAggregate aggregate) {

    aggregate.setStatus("processed");

  }

  @WorkflowTask
  public void raiseBpmnError(
      final TaskProcessingAggregate aggregate) {

    aggregate.setStatus("bpmn-error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void failTask(
      final TaskProcessingAggregate aggregate) {

    aggregate.setStatus("must-never-be-visible");
    throw new IllegalStateException("something broke");

  }

  @WorkflowTask
  public void asyncTask(
      final TaskProcessingAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setStatus("async-started");
    aggregate.setTaskId(taskId);

  }

  @WorkflowTask
  public void bindParameters(
      final TaskProcessingAggregate aggregate,
      @TaskParam("approval") final String approval,
      @MultiInstanceIndex("items") final int index,
      @MultiInstanceTotal("items") final int total,
      @MultiInstanceElement("items") final Object element) {

    aggregate.setStatus(approval);
    aggregate.setIndex(index);
    aggregate.setTotal(total);
    aggregate.setElement(element);

  }

}
