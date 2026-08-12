package io.vanillabp.integration.test.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the aggregate of the BPMS-initiated-start acceptance
 * test. Aggregates are copied on save/load so un-saved mutations never leak into
 * the store.
 */
@ApplicationScoped
public class StartAggregatePersistence implements AggregatePersistenceAware<StartAggregate> {

  private final Map<String, StartAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<StartAggregate> getAggregateClass() {

    return StartAggregate.class;

  }

  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public Object getAggregateId(
      final StartAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public StartAggregate save(
      final StartAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public StartAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * @param aggregateId The aggregate's ID
   * @return What is stored under that ID, or <code>null</code>
   */
  public StartAggregate stored(
      final String aggregateId) {

    return aggregates.get(aggregateId);

  }

  /**
   * @return How many aggregates the store holds
   */
  public int count() {

    return aggregates.size();

  }

  public void put(
      final StartAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));

  }

  private static StartAggregate copyOf(
      final StartAggregate aggregate) {

    final var copy = new StartAggregate();
    copy.setId(aggregate.getId());
    copy.setRegion(aggregate.getRegion());
    copy.setAmount(aggregate.getAmount());
    copy.setStartedBy(aggregate.getStartedBy());
    return copy;

  }

}
