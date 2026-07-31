package io.vanillabp.integration.utils.impl;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
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

  @Override
  public String getAggregateIdName() {

    return springDataUtil
        .getIdName(aggregateClass);

  }

  @Override
  public Class<?> getAggregateIdType() {

    // Spring Data is authoritative for the ID type (covers property access,
    // non-"id"-named IDs etc.); if the aggregate is not a managed entity, null is
    // the contract's "not determinable" answer (custom persistence layers based on
    // this class own the serialized form)
    try {
      return springDataUtil.getIdType(aggregateClass);
    } catch (Exception e) {
      return null;
    }

  }

  @Override
  public A loadById(
      final Object aggregateId) {

    return springDataUtil
        .getRepository(aggregateClass)
        .findById(aggregateId)
        .orElse(null);

  }

}
