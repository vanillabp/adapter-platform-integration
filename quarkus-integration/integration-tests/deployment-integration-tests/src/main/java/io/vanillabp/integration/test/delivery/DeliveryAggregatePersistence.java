package io.vanillabp.integration.test.delivery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory persistence for the inbound-idempotency test's aggregate, copying on
 * save/load so un-saved mutations never leak into the store.
 */
@ApplicationScoped
public class DeliveryAggregatePersistence implements AggregatePersistenceAware<DeliveryAggregate> {

  private final Map<String, DeliveryAggregate> aggregates = new ConcurrentHashMap<>();

  @Override
  public Class<DeliveryAggregate> getAggregateClass() {

    return DeliveryAggregate.class;

  }

  @Override
  public DeliveryAggregate save(
      final DeliveryAggregate aggregate) {

    aggregates.put(aggregate.getId(), copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final DeliveryAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public DeliveryAggregate loadById(
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

    final var aggregate = new DeliveryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    aggregates.put(id, aggregate);

  }

  /**
   * @param id The aggregate's ID
   * @return The stored aggregate
   */
  public DeliveryAggregate get(
      final String id) {

    return aggregates.get(id);

  }

  private static DeliveryAggregate copyOf(
      final DeliveryAggregate aggregate) {

    final var copy = new DeliveryAggregate();
    copy.setId(aggregate.getId());
    copy.setStatus(aggregate.getStatus());
    copy.setInvocations(aggregate.getInvocations());
    return copy;

  }

}
