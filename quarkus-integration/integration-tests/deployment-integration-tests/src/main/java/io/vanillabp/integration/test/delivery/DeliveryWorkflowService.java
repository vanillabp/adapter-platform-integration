package io.vanillabp.integration.test.delivery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the inbound-idempotency test: one method per outcome a
 * repeated delivery has to be answered with, each counting its invocations.
 */
@ApplicationScoped
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
   * redeliveries is what story 97 keeps alive.
   */
  @WorkflowTask
  public void awaitCompletion(
      final DeliveryAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("awaiting-completion");

  }

  @WorkflowTask
  public void undeduplicatedTask(
      final DeliveryAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("processed-again");

  }

}
