package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;

import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.migration.transaction.TransactionForm;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Cancellations VanillaBP works out for itself, for the BPMS which do not report one.
 * <p>
 * Two callers ask for the same thing and get it from here. The end of a workflow knows
 * that everything the application still believes is open in it is gone
 * ({@code WorkflowTaskRegistry#workflowEnded}), and a wake-up asks the BPMS about the
 * other tasks of its workflow and learns which of them the engine does not have any more
 * ({@code WorkflowTaskRegistry#reportTasksTheBpmsNoLongerHas}). What follows from either
 * answer is one thing, so it is written once.
 *
 * <h2>Claim first, deliver second</h2>
 *
 * Each record is handled in a transaction of its own. Inside it the record is CLAIMED with
 * {@link TaskDeliveryLog#markTaskClosed}, and the delivery of
 * {@link TaskEvent.Event#CANCELED} follows only where the claim took, in the same
 * transaction. That order is what makes two application instances safe: on SQL the second
 * update waits on the row lock and reads zero afterwards, on MongoDB the second write
 * conflicts and the retry finds the filter no longer matching. Why the count of that call
 * is a claim rather than a statistic is decision 72 in the repository's DECISIONS.md.
 * <p>
 * A handler which throws rolls its transaction back, the claim goes with it, and the task
 * is derived again the next time somebody looks. That is at-least-once, which is what the
 * platform promises everywhere else. The failure stays with that one record: neither the
 * end of a workflow nor the job which woke the application up is lost because a derived
 * cancellation failed.
 *
 * <h2>What such a delivery cannot carry</h2>
 *
 * The element is gone by the time anybody derives anything, so a
 * <code>&#64;TaskParam</code> and a multi-instance value reach the method as
 * <code>null</code>. The methods which really declare one are named at the boot, see
 * {@code WorkflowTaskRegistry#reportWhatACancelationCannotCarry}.
 */
@Slf4j
public final class DerivedCancelations {

  private DerivedCancelations() {

  }

  /**
   * Derives a cancellation for each of the given records, one transaction at a time.
   *
   * @param records The open records to report as canceled, oldest first
   * @param deliveryLog The store holding them
   * @param transactionRunner The runner serving the workflow aggregate
   * @param workflowModuleId The workflow module of the workflow
   * @param invoker What delivers the event to the application
   * @param why What is said in a log line about a record which could not be reported
   * @return How many cancellations were really delivered
   */
  public static int reportAsCanceled(
      final List<TaskDelivery> records,
      final TaskDeliveryLog deliveryLog,
      final TransactionRunner transactionRunner,
      final String workflowModuleId,
      final WorkflowTaskInvoker invoker,
      final String why) {

    var delivered = 0;
    for (final var record : records) {
      // a record whose BPMS named no task cannot be claimed and names nothing the
      // application could be told about - it is invisible to this whole mechanism
      if (record.taskId() == null) {
        continue;
      }
      if (reportAsCanceled(record, deliveryLog, transactionRunner, workflowModuleId, invoker, why)) {
        ++delivered;
      }
    }
    return delivered;

  }

  /**
   * Derives the cancellation of one record: claim it, and deliver the event where the
   * claim took.
   *
   * @return Whether this call delivered the event
   */
  private static boolean reportAsCanceled(
      final TaskDelivery record,
      final TaskDeliveryLog deliveryLog,
      final TransactionRunner transactionRunner,
      final String workflowModuleId,
      final WorkflowTaskInvoker invoker,
      final String why) {

    try {
      return AggregateWrite
          .inTransaction(
              transactionRunner,
              TransactionForm.NEW,
              workflowModuleId,
              record.bpmnProcessId(),
              record.workflowAggregateId(),
              "reporting task '%s' as canceled".formatted(record.taskId()),
              () -> claimAndDeliver(record, deliveryLog, workflowModuleId, invoker, why));
    } catch (final RuntimeException failure) {
      // the claim went back with the transaction, so the record is open again and the
      // next look derives this task once more. What must not happen is that the caller
      // loses its own work over it - the end of a workflow and the job which woke the
      // application up are both worth more than one derived cancellation
      log
          .warn(
              "Task '{}' of workflow '{}' (BPMN process '{}' of workflow module '{}') {}, but "
                  + "reporting it as canceled failed - its record stays open and the next look at "
                  + "that workflow reports it again",
              record.taskId(),
              record.workflowId(),
              record.bpmnProcessId(),
              workflowModuleId,
              why,
              failure);
      return false;
    }

  }

  private static boolean claimAndDeliver(
      final TaskDelivery record,
      final TaskDeliveryLog deliveryLog,
      final String workflowModuleId,
      final WorkflowTaskInvoker invoker,
      final String why) {

    final var claimed = deliveryLog
        .markTaskClosed(
            workflowModuleId,
            record.bpmnProcessId(),
            record.workflowAggregateId(),
            record.taskId());
    if (claimed == 0) {
      // somebody else took this record: another instance of this application derived the
      // same cancellation, or the application completed the task a moment ago
      log
          .debug(
              "Task '{}' of workflow '{}' was closed by somebody else - not reporting it as canceled",
              record.taskId(),
              record.workflowId());
      return false;
    }
    log
        .info(
            "Task '{}' of workflow '{}' (BPMN process '{}' of workflow module '{}') {} - reporting "
                + "it to the application as canceled",
            record.taskId(),
            record.workflowId(),
            record.bpmnProcessId(),
            workflowModuleId,
            why);
    invoker.invokeWorkflowTask(workflowModuleId, record.bpmnProcessId(), contextOf(record));
    return true;

  }

  /**
   * The delivery the application sees, built from what the record kept. Everything the
   * job of the element carried is gone with the element, so a <code>&#64;TaskParam</code>
   * and a multi-instance value are absent here.
   * <p>
   * It runs in the transaction this derivation opened
   * ({@link TaskInvocationContext#runInCurrentTransaction()}), so the claim and whatever
   * the method writes commit together. The delivery is named after the task rather than
   * after a job, because there is no job: a second derivation of the same task therefore
   * finds the record of the first one and is answered from it instead of running the
   * method again.
   */
  private static TaskInvocationContext contextOf(
      final TaskDelivery record) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return record.adapterId();
      }

      @Override
      public String getTaskDefinition() {
        return record.taskDefinition();
      }

      @Override
      public String getBpmnElementId() {
        return record.bpmnElementId();
      }

      @Override
      public String getWorkflowAggregateId() {
        return record.workflowAggregateId();
      }

      @Override
      public String getWorkflowId() {
        return record.workflowId();
      }

      @Override
      public String getTaskId() {
        return record.taskId();
      }

      @Override
      public String getDeliveryId() {
        return record.taskId();
      }

      @Override
      public TaskEvent.Event getTaskEvent() {
        return TaskEvent.Event.CANCELED;
      }

      @Override
      public boolean runInCurrentTransaction() {
        return true;
      }

    };

  }

}
