package io.vanillabp.integration.test.activation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the activation-identity test's aggregate, copying on
 * save/load so un-saved mutations never leak into the store.
 */
@ApplicationScoped
public class ActivationAggregatePersistence implements AggregatePersistenceAware<ActivationAggregate> {

  private final Map<String, ActivationAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<ActivationAggregate> getAggregateClass() {

    return ActivationAggregate.class;

  }

  @Override
  public ActivationAggregate save(
      final ActivationAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final ActivationAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public ActivationAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * Puts an aggregate into the store, the way a workflow start would have.
   *
   * @param id The aggregate's id
   */
  public void store(
      final String id) {

    final var aggregate = new ActivationAggregate();
    aggregate.setId(id);
    aggregates.put(id, aggregate);

  }

  /**
   * @param id The aggregate's id
   * @return The stored aggregate
   */
  public ActivationAggregate get(
      final String id) {

    return aggregates.get(id);

  }

  private static ActivationAggregate copyOf(
      final ActivationAggregate aggregate) {

    final var copy = new ActivationAggregate();
    copy.setId(aggregate.getId());
    copy.setCorrelations(aggregate.getCorrelations());
    return copy;

  }

}
