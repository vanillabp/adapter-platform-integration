package io.vanillabp.integration.test.deployment;

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

}
