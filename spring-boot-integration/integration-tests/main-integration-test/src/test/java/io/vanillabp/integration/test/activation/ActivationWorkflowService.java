package io.vanillabp.integration.test.activation;

import org.springframework.beans.factory.annotation.Autowired;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A handler which correlates a message while it runs, which is the shape the story is
 * about: the correlation id comes from business data and does not have to differ
 * between the elements of a multi-instance activity, so what tells the elements apart
 * can only come from the BPMS.
 */
@WorkflowService(
    workflowAggregateClass = ActivationAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ActivationProcess"))
public class ActivationWorkflowService {

  /**
   * The correlation id every element of the multi-instance activity uses - one partner
   * asked once per element, and business data which is legitimately equal.
   */
  public static final String CORRELATION_ID = "partner-42";

  @Autowired
  private ProcessService<ActivationAggregate> processService;

  @WorkflowTask
  public void requestOffer(
      final ActivationAggregate aggregate) {

    aggregate.setCorrelations(aggregate.getCorrelations() + 1);
    processService.correlateMessage(aggregate, "OfferRequested", CORRELATION_ID);

  }

}
