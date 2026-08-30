package io.vanillabp.integration.test.delivery;

import java.util.concurrent.atomic.AtomicReference;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the inbound-idempotency test: one method per outcome a repeated
 * delivery has to be answered with, each counting its own invocations - the business code
 * a redelivery must NOT run again.
 */
@WorkflowService(
    workflowAggregateClass = DeliveryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DeliveryProcess"))
public class DeliveryWorkflowService {

  @WorkflowTask
  public void processTask(
      final DeliveryAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("processed");

  }

  @WorkflowTask
  public void raiseBpmnError(
      final DeliveryAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("bpmn-error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void failTask(
      final DeliveryAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("must-never-be-visible");
    throw new IllegalStateException("something broke");

  }

  /**
   * A task the application completes later, so it stays open from the BPMS' point of view
   * and is redelivered until somebody completes it - the record which answers those
   * redeliveries is what the open-task retention keeps alive.
   */
  @WorkflowTask
  public void awaitCompletion(
      final DeliveryAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("awaiting-completion");

  }

  /**
   * What {@link #concurrentTask(DeliveryAggregate)} does before it returns, so a test
   * can hold one delivery inside the handler while it hands the same task out a second
   * time. Nothing, until a test puts something in.
   */
  public static final AtomicReference<Runnable> WHILE_THE_CONCURRENT_TASK_RUNS = new AtomicReference<>(() -> {
  });

  /**
   * A task whose handler can be held inside its transaction, which is what two
   * deliveries of one task overlapping each other needs.
   */
  @WorkflowTask
  public void concurrentTask(
      final DeliveryAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("processed-concurrently");
    WHILE_THE_CONCURRENT_TASK_RUNS.get().run();

  }

  @WorkflowTask
  public void undeduplicatedTask(
      final DeliveryAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("processed-again");

  }

}
