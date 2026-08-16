package io.vanillabp.integration.test.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence of the multi-instance resolver test's aggregate.
 */
@ApplicationScoped
public class ResolverAggregatePersistence implements AggregatePersistenceAware<ResolverAggregate> {

  private final Map<String, ResolverAggregate> aggregates = new ConcurrentHashMap<>();

  public void seed(
      final String id) {

    final var aggregate = new ResolverAggregate();
    aggregate.setId(id);
    aggregates.put(id, aggregate);

  }

  public ResolverAggregate stored(
      final String id) {

    return aggregates.get(id);

  }

  @Override
  public Class<ResolverAggregate> getAggregateClass() {

    return ResolverAggregate.class;

  }

  @Override
  public ResolverAggregate save(
      final ResolverAggregate aggregate) {

    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final ResolverAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public ResolverAggregate loadById(
      final Object aggregateId) {

    return aggregates.get((String) aggregateId);

  }

}
