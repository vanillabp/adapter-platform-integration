package io.vanillabp.integration.test;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public class WorkflowService {

  @Inject
  ProcessService<Aggregate> processService;

  public Aggregate startWorkflow(
      final String content) {

    final var aggregate = new Aggregate();
    aggregate.setContent(content);
    return processService.startWorkflow(aggregate);

  }

  /**
   * Starts the workflow (again) for an aggregate which is already stored. It is used to test
   * that scheduling phase two is idempotent.
   *
   * @param aggregate The aggregate to start the workflow for
   * @return The attached aggregate
   */
  public Aggregate startWorkflowAgain(
      final Aggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

}
