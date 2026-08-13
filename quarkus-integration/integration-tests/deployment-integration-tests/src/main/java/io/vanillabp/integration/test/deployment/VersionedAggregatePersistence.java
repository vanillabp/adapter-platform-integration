package io.vanillabp.integration.test.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the process-version acceptance test's aggregate.
 */
@ApplicationScoped
public class VersionedAggregatePersistence implements AggregatePersistenceAware<VersionedAggregate> {

  private final Map<String, VersionedAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<VersionedAggregate> getAggregateClass() {

    return VersionedAggregate.class;

  }

  @Override
  public VersionedAggregate save(
      final VersionedAggregate aggregate) {

    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final VersionedAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public VersionedAggregate loadById(
      final Object aggregateId) {

    return aggregates.get(aggregateId);

  }

  /**
   * @param id The aggregate ID
   * @return A stored aggregate the test can hand to a task invocation
   */
  public VersionedAggregate seed(
      final String id) {

    final var aggregate = new VersionedAggregate();
    aggregate.setId(id);
    aggregates.put(id, aggregate);
    return aggregate;

  }

  /**
   * @param id The aggregate ID
   * @return The stored aggregate
   */
  public VersionedAggregate stored(
      final String id) {

    return aggregates.get(id);

  }

}
