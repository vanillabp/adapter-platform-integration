package io.vanillabp.integration.test;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public class WorkflowService {
}
