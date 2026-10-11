package io.vanillabp.integration.test.samples.nosubclass;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.inject.Singleton;

/**
 * Nothing in this test reaches a workflow aggregate. So the persistence only answers what
 * it has to.
 */
@Singleton
public class LonelyAggregatePersistence implements AggregatePersistenceAware<LonelyAggregate> {

  @Override
  public Class<LonelyAggregate> getAggregateClass() {
    return LonelyAggregate.class;
  }

  @Override
  public LonelyAggregate save(
      final LonelyAggregate aggregate) {
    return aggregate;
  }

  @Override
  public Object getAggregateId(
      final LonelyAggregate aggregate) {
    return null;
  }

}
