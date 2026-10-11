package io.vanillabp.integration.test.activation;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A handler which correlates a message while it runs. This is the case the story is about.
 * The correlation id comes from business data. It does not have to differ between the
 * elements of a multi-instance activity. So only the BPMS can tell the elements apart.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ActivationAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ActivationProcess"))
public class ActivationWorkflowService {

  /**
   * The correlation id which every element of the multi-instance activity uses. One partner
   * is asked once per element, and the business data is equal for a good reason.
   */
  public static final String CORRELATION_ID = "partner-42";

  @Inject
  ProcessService<ActivationAggregate> processService;

  @WorkflowTask
  public void requestOffer(
      final ActivationAggregate aggregate) {

    aggregate.setCorrelations(aggregate.getCorrelations() + 1);
    processService.correlateMessage(aggregate, "OfferRequested", CORRELATION_ID);

  }

}
