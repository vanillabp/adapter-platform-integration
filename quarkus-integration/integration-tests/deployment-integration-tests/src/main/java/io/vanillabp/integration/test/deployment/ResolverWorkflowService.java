package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ResolverAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ResolverProcess"))
public class ResolverWorkflowService {

  @WorkflowTask
  public void iterate(
      final ResolverAggregate aggregate,
      @MultiInstanceElement(resolverBean = IterationResolver.class) final String iteration) {

    aggregate.setResolved(iteration);

  }

}
