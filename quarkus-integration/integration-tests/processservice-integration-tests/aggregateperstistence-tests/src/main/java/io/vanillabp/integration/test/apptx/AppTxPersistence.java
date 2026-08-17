package io.vanillabp.integration.test.apptx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The application's own aggregate store - in a test that is a map, in reality an event
 * store, a ledger or a service behind an API.
 */
@ApplicationScoped
public class AppTxPersistence implements AggregatePersistenceAware<AppTxAggregate> {

  private final Map<String, AppTxAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<AppTxAggregate> getAggregateClass() {

    return AppTxAggregate.class;

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public AppTxAggregate save(
      final AppTxAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final AppTxAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public AppTxAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * @param id The aggregate's ID
   * @return What is stored, as stored - the tests read the store, not the object they
   *         handed over
   */
  public AppTxAggregate stored(
      final String id) {

    return aggregates.get(id);

  }

  public void clear() {

    aggregates.clear();

  }

  private static AppTxAggregate copyOf(
      final AppTxAggregate aggregate) {

    final var copy = new AppTxAggregate();
    copy.setId(aggregate.getId());
    copy.setStatus(aggregate.getStatus());
    copy.setInvocations(aggregate.getInvocations());
    return copy;

  }

}
