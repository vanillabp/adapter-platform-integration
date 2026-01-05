package io.vanillabp.integration.test;

import io.vanillabp.spi.process.AggregatePersistenceAware;

public class NoBeanAggregatePersistence implements AggregatePersistenceAware<Aggregate> {

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
