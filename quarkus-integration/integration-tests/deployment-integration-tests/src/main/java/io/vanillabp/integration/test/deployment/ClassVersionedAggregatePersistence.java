package io.vanillabp.integration.test.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the class-level version acceptance test's aggregate.
 */
@ApplicationScoped
public class ClassVersionedAggregatePersistence implements AggregatePersistenceAware<ClassVersionedAggregate> {

  private final Map<String, ClassVersionedAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<ClassVersionedAggregate> getAggregateClass() {

    return ClassVersionedAggregate.class;

  }

  @Override
  public ClassVersionedAggregate save(
      final ClassVersionedAggregate aggregate) {

    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final ClassVersionedAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public ClassVersionedAggregate loadById(
      final Object aggregateId) {

    return aggregates.get(aggregateId);

  }

  /**
   * @param id The aggregate ID
   * @return A stored aggregate the test can hand to a task invocation
   */
  public ClassVersionedAggregate seed(
      final String id) {

    final var aggregate = new ClassVersionedAggregate();
    aggregate.setId(id);
    aggregates.put(id, aggregate);
    return aggregate;

  }

  /**
   * @param id The aggregate ID
   * @return The stored aggregate
   */
  public ClassVersionedAggregate stored(
      final String id) {

    return aggregates.get(id);

  }

}
