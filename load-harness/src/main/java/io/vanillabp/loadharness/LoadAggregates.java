package io.vanillabp.loadharness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * Keeps the aggregates of the load process in memory. The aggregate belongs to the
 * application, and what an application's persistence costs is not what the run measures. So
 * this store costs no statement, and every statement the run counts is one VanillaBP made.
 * <p>
 * A finished workflow leaves its aggregate here until {@link #forget} is called. The driver
 * does that at the end of each workflow, so the live set after a forced collection says
 * something about VanillaBP and not about this map.
 */
public class LoadAggregates implements AggregatePersistenceAware<LoadAggregate> {

  private final Map<String, LoadAggregate> aggregates = new ConcurrentHashMap<>();

  /**
   * An empty store.
   */
  public LoadAggregates() {
  }

  @Override
  public Class<LoadAggregate> getAggregateClass() {

    return LoadAggregate.class;

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
      final LoadAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public LoadAggregate save(
      final LoadAggregate aggregate) {

    aggregates.put(aggregate.getId(), aggregate.copy());
    return aggregate;

  }

  @Override
  public LoadAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(String.valueOf(aggregateId));
    return stored == null
        ? null
        : stored.copy();

  }

  /**
   * Removes the aggregate of a finished workflow.
   *
   * @param aggregateId The id of the aggregate
   */
  public void forget(
      final String aggregateId) {

    aggregates.remove(aggregateId);

  }

  /**
   * How many aggregates the store holds.
   *
   * @return The number of aggregates
   */
  public int size() {

    return aggregates.size();

  }

}
