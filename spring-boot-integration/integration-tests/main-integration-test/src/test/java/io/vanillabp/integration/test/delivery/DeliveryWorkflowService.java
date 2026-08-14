package io.vanillabp.integration.test.delivery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
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

  @WorkflowTask
  public void undeduplicatedTask(
      final DeliveryAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("processed-again");

  }

}
