package io.vanillabp.integration.test.deployment;

import io.quarkus.narayana.jta.QuarkusTransaction;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;

/**
 * The workflow service of the task-processing acceptance test (story 21a): one
 * <code>&#64;WorkflowTask</code> method per user-visible variation. The happy-path
 * handler additionally proves the platform contract for handler invocations on
 * adapter threads: a JTA transaction is active and the CDI request context was
 * activated (see {@link RequestScopedProbe}).
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskProcess"))
public class TaskWorkflowService {

  @Inject
  ProcessService<TaskAggregate> processService;

  @Inject
  RequestScopedProbe requestScopedProbe;

  @WorkflowTask
  public void processTask(
      final TaskAggregate aggregate) {

    if (QuarkusTransaction.getStatus() == Status.STATUS_NO_TRANSACTION) {
      throw new IllegalStateException("no JTA transaction active in @WorkflowTask handler");
    }
    // throws ContextNotActiveException if the platform did not activate the
    // request context around the handler invocation
    aggregate.setRequestScopedProbe(requestScopedProbe.ping());
    aggregate.setStatus("processed");

  }

  @WorkflowTask
  public void raiseBpmnError(
      final TaskAggregate aggregate) {

    aggregate.setStatus("bpmn-error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  /**
   * What the logging context held while the handler ran - the assertion of the MDC
   * VanillaBP puts around every delivery (story 92).
   */
  public static final java.util.Map<String, String> MDC_DURING_TASK = new java.util.concurrent.ConcurrentHashMap<>();

  @WorkflowTask
  public void recordMdc(
      final TaskAggregate aggregate) {

    MDC_DURING_TASK.clear();
    final var context = org.slf4j.MDC.getCopyOfContextMap();
    if (context != null) {
      MDC_DURING_TASK.putAll(context);
    }
    aggregate.setStatus("mdc-recorded");

  }

  @WorkflowTask
  public void failTask(
      final TaskAggregate aggregate) {

    aggregate.setStatus("must-never-be-visible");
    throw new IllegalStateException("something broke");

  }

  @WorkflowTask
  public void asyncTask(
      final TaskAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setStatus("async-started");
    aggregate.setTaskId(taskId);

  }

  @WorkflowTask
  public void bindParameters(
      final TaskAggregate aggregate,
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
