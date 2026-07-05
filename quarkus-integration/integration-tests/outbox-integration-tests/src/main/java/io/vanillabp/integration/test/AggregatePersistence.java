package io.vanillabp.integration.test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A simple in-memory aggregate persistence assigning generated IDs (like a JPA entity
 * with a generated ID would have).
 */
@ApplicationScoped
public class AggregatePersistence implements AggregatePersistenceAware<Aggregate> {

  private final Map<Long, Aggregate> aggregates = new ConcurrentHashMap<>();

  private final AtomicLong idSequence = new AtomicLong(0);

  @Override
  public Class<Aggregate> getAggregateClass() {

    return Aggregate.class;

  }

  @Override
  public Aggregate save(
      final Aggregate aggregate) {

    if (aggregate.getId() == null) {
      aggregate.setId(idSequence.incrementAndGet());
    }
    aggregates.put(aggregate.getId(), aggregate);
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final Aggregate aggregate) {

    return aggregate.getId();

  }

}
