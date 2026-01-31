package io.vanillabp.integration.utils.impl;

import io.vanillabp.integration.spi.aggregate.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SpringDataUtilBasedAggregatePersistenceSupport<A> implements AggregatePersistenceAware<A> {

  private final SpringDataUtil springDataUtil;

  private final Class<A> aggregateClass;

  @Override
  public Class<A> getAggregateClass() {

    return aggregateClass;

  }

  @Override
  public A save(
      A aggregate) {

    return springDataUtil
        .getRepository(aggregate)
        .save(aggregate);

  }

  @Override
  public Object getAggregateId(
      A aggregate) {

    return springDataUtil
        .getId(aggregate);

  }

}
