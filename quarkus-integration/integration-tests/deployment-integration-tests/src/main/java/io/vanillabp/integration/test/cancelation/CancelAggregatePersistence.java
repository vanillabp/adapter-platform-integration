package io.vanillabp.integration.test.cancelation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the derived-cancellation tests, copying on save and on load
 * so a handler which threw leaves nothing of what it changed behind.
 */
@ApplicationScoped
public class CancelAggregatePersistence implements AggregatePersistenceAware<CancelAggregate> {

  private final Map<String, CancelAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<CancelAggregate> getAggregateClass() {

    return CancelAggregate.class;

  }

  @Override
  public CancelAggregate save(
      final CancelAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final CancelAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public CancelAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * Stores an aggregate the test starts from, without going through a workflow start.
   *
   * @param id The aggregate's ID
   */
  public void store(
      final String id) {

    final var aggregate = new CancelAggregate();
    aggregate.setId(id);
    aggregates.put(id, aggregate);

  }

  /**
   * @param id The aggregate's ID
   * @return The stored aggregate
   */
  public CancelAggregate get(
      final String id) {

    return aggregates.get(id);

  }

  private static CancelAggregate copyOf(
      final CancelAggregate aggregate) {

    final var copy = new CancelAggregate();
    copy.setId(aggregate.getId());
    copy.setWhatArrived(aggregate.getWhatArrived());
    return copy;

  }

}
