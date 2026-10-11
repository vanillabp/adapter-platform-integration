package io.vanillabp.integration.test.transaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A workflow service written the naive Spring way. Its handler carries
 * {@code @Transactional}, like an ordinary service method. The annotation joins the
 * transaction in which VanillaBP runs the handler. So a {@code TaskException} would
 * throw away every change to the workflow aggregate. The application must not boot
 * with this service.
 */
@Service
@WorkflowService(
    workflowAggregateClass = TransactionalAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TransactionalProcess"))
public class TransactionalWorkflowService {

  @WorkflowTask
  @Transactional
  public void assessRisk(
      final TransactionalAggregate aggregate) {

    aggregate.setStatus("assessed");

  }

}
