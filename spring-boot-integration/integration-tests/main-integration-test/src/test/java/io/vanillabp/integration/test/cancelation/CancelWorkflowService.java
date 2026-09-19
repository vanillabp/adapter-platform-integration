package io.vanillabp.integration.test.cancelation;

import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the derived-cancellation tests: two asynchronous tasks, one of
 * which asks to hear about its cancellation, and a method for the end of the workflow.
 * Everything which arrives is written into the aggregate in the order it arrived, so a
 * test reads the order instead of guessing it.
 */
@WorkflowService(
    workflowAggregateClass = CancelAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "CancelProcess"))
public class CancelWorkflowService {

  /**
   * How many of the next cancellations of {@link #awaitSignature} have to fail. A test
   * sets it to make a handler throw, which is what leaves the record open and has the
   * task derived again later.
   */
  public static final AtomicInteger CANCELATIONS_WHICH_FAIL = new AtomicInteger();

  /**
   * A task the application completes later and which wants to hear about its
   * cancellation.
   *
   * @param aggregate The workflow aggregate
   * @param taskId The BPMS' identity of the task
   * @param event Which of the two events this delivery is about
   */
  @WorkflowTask
  public void awaitSignature(
      final CancelAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent({
          TaskEvent.Event.CREATED, TaskEvent.Event.CANCELED
      }) final TaskEvent.Event event) {

    if (event != TaskEvent.Event.CANCELED) {
      return;
    }
    if (CANCELATIONS_WHICH_FAIL.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
      throw new IllegalStateException("the handler of a canceled task threw");
    }
    aggregate.arrived(taskId);

  }

  /**
   * The same task once more, so a test can have two of them open at the same time.
   *
   * @param aggregate The workflow aggregate
   * @param taskId The BPMS' identity of the task
   * @param event Which of the two events this delivery is about
   */
  @WorkflowTask
  public void awaitApproval(
      final CancelAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent({
          TaskEvent.Event.CREATED, TaskEvent.Event.CANCELED
      }) final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CANCELED) {
      aggregate.arrived(taskId);
    }

  }

  /**
   * A task which reads a value of the element it runs in, which is what a derived
   * cancellation cannot carry - the method the boot names.
   *
   * @param aggregate The workflow aggregate
   * @param taskId The BPMS' identity of the task
   * @param event Which of the two events this delivery is about
   * @param amount A value of the element, absent in a derived cancellation
   */
  @WorkflowTask
  public void awaitPayment(
      final CancelAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent({
          TaskEvent.Event.CREATED, TaskEvent.Event.CANCELED
      }) final TaskEvent.Event event,
      @TaskParam("amount") final String amount) {

    if (event == TaskEvent.Event.CANCELED) {
      aggregate.arrived(taskId);
    }

  }

  /**
   * A task which never asked to hear about a cancellation.
   *
   * @param aggregate The workflow aggregate
   * @param taskId The BPMS' identity of the task
   */
  @WorkflowTask
  public void awaitDelivery(
      final CancelAggregate aggregate,
      @TaskId final String taskId) {

    // nothing - the task stays open until somebody completes it

  }

  /**
   * What runs after every task of the ended workflow was reported.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowEnded
  public void theWorkflowEnded(
      final CancelAggregate aggregate) {

    aggregate.arrived("ended");

  }

}
