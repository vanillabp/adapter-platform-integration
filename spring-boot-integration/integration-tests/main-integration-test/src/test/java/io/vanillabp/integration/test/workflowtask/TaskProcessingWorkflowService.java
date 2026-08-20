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

  /**
   * What the logging context held while the handler ran - the assertion of the MDC
   * VanillaBP puts around every delivery (story 92). A map rather than a log line,
   * because a test has to see the keys and not their rendering.
   */
  public static final java.util.Map<String, String> MDC_DURING_TASK = new java.util.concurrent.ConcurrentHashMap<>();

  @WorkflowTask
  public void recordMdc(
      final TaskProcessingAggregate aggregate) {

    captureMdc();
    aggregate.setStatus("mdc-recorded");

  }

  @WorkflowTask
  public void recordMdcAndFail(
      final TaskProcessingAggregate aggregate) {

    captureMdc();
    throw new IllegalStateException("failed after recording the context");

  }

  private static void captureMdc() {

    MDC_DURING_TASK.clear();
    final var context = org.slf4j.MDC.getCopyOfContextMap();
    if (context != null) {
      MDC_DURING_TASK.putAll(context);
    }

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
