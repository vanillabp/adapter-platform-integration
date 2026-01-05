package io.vanillabp.integration.test;

import io.vanillabp.spi.process.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AggregatePersistence implements AggregatePersistenceAware<Aggregate> {

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
