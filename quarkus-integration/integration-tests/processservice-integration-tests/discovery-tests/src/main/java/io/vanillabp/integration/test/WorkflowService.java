package io.vanillabp.integration.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public class WorkflowService implements AggregatePersistenceAware<Aggregate> {

  @Override
  public Class<Aggregate> getAggregateClass() {
    return null;
  }

  @Override
  public Aggregate save(
      Aggregate aggregate) {
    return null;
  }

  @Override
  public Object getAggregateId(
      Aggregate aggregate) {
    return null;
  }

}
