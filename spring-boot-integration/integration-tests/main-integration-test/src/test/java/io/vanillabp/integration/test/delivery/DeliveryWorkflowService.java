package io.vanillabp.integration.test.delivery;

import java.util.concurrent.atomic.AtomicReference;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the inbound-idempotency test. It has one method per outcome which
 * a repeated delivery has to be answered with. Each method counts its own calls. They stand
 * for the business code which a redelivery must NOT run again.
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
   * A task which the application completes later. So it stays open from the BPMS' point of
   * view, and it is redelivered until somebody completes it. The record which answers those
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
   * A task which the application completes later. It also asks to hear about its
   * cancellation, so a cancellation of the BPMS reaches this method. The cancellation tests
   * read what the delivery of {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED} does
   * to the record of that task.
   *
   * @param aggregate The workflow aggregate
   * @param taskId The BPMS' identity of the task
   * @param event Which of the two events this delivery is about
   */
  @WorkflowTask
  public void cancelableTask(
      final DeliveryAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent({
          TaskEvent.Event.CREATED, TaskEvent.Event.CANCELED
      }) final TaskEvent.Event event) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    if (event != TaskEvent.Event.CANCELED) {
      aggregate.setStatus("awaiting-completion");
      return;
    }
    aggregate.setStatus("canceled");
    aggregate
        .setCanceledTasks(
            aggregate.getCanceledTasks() == null
                ? taskId
                : aggregate.getCanceledTasks()
                    + ","
                    + taskId);

  }

  /**
   * What {@link #concurrentTask(DeliveryAggregate)} does before it returns. So a test can
   * hold one delivery inside the handler while it hands out the same task a second time. It
   * does nothing until a test puts something in.
   */
  public static final AtomicReference<Runnable> WHILE_THE_CONCURRENT_TASK_RUNS = new AtomicReference<>(() -> {
  });

  /**
   * A task whose handler can be held inside its transaction. A test needs this to make two
   * deliveries of one task overlap.
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
