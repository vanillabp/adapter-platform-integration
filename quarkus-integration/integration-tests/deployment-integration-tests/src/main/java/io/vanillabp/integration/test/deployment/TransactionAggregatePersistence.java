package io.vanillabp.integration.test.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the transaction-contract acceptance test's aggregate.
 * Aggregates are copied on save/load so un-saved mutations never leak into the store.
 */
@ApplicationScoped
public class TransactionAggregatePersistence implements AggregatePersistenceAware<TransactionAggregate> {

  private final Map<String, TransactionAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<TransactionAggregate> getAggregateClass() {

    return TransactionAggregate.class;

  }

  @Override
  public TransactionAggregate save(
      final TransactionAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final TransactionAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public TransactionAggregate loadById(
      final Object aggregateId) {

    final var stored = aggregates.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  /**
   * Direct store access for test assertions (only saved state is visible).
   *
   * @param id The aggregate's ID
   * @return The stored aggregate or <code>null</code>
   */
  public TransactionAggregate stored(
      final String id) {

    return aggregates.get(id);

  }

  /**
   * Seeds an aggregate into the store, as if a workflow had been started.
   *
   * @param id The aggregate's ID
   * @return The seeded aggregate
   */
  public TransactionAggregate seed(
      final String id) {

    final var aggregate = new TransactionAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    aggregates.put(id, aggregate);
    return aggregate;

  }

  private static TransactionAggregate copyOf(
      final TransactionAggregate aggregate) {

    final var copy = new TransactionAggregate();
    copy.setId(aggregate.getId());
    copy.setStatus(aggregate.getStatus());
    return copy;

  }

}
