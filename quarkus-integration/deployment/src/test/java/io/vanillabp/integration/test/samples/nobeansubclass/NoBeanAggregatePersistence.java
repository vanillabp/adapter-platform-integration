package io.vanillabp.integration.test.samples.nobeansubclass;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.inject.Singleton;

/**
 * Nothing in this test reaches a workflow aggregate. So the persistence only answers what
 * it has to.
 */
@Singleton
public class NoBeanAggregatePersistence implements AggregatePersistenceAware<NoBeanAggregate> {

  @Override
  public Class<NoBeanAggregate> getAggregateClass() {
    return NoBeanAggregate.class;
  }

  @Override
  public NoBeanAggregate save(
      final NoBeanAggregate aggregate) {
    return aggregate;
  }

  @Override
  public Object getAggregateId(
      final NoBeanAggregate aggregate) {
    return null;
  }

}
