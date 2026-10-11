package io.vanillabp.integration.test.samples.annotatedbeanbase;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.inject.Singleton;

/**
 * Nothing in this test reaches a workflow aggregate. So the persistence only answers what
 * it has to.
 */
@Singleton
public class BeanBaseAggregatePersistence implements AggregatePersistenceAware<BeanBaseAggregate> {

  @Override
  public Class<BeanBaseAggregate> getAggregateClass() {
    return BeanBaseAggregate.class;
  }

  @Override
  public BeanBaseAggregate save(
      final BeanBaseAggregate aggregate) {
    return aggregate;
  }

  @Override
  public Object getAggregateId(
      final BeanBaseAggregate aggregate) {
    return null;
  }

}
