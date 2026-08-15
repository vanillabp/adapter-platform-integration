package io.vanillabp.integration.test.persistence;

import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An implementation written by the application for an aggregate which HAS a Panache
 * repository: VanillaBP has to use this one, the escape hatch has to stay one. Every
 * call is counted so the test can prove which implementation ran.
 */
@ApplicationScoped
public class ApplicationRepositoryPersistence implements AggregatePersistenceAware<RepositoryAggregate> {

  private final AtomicInteger saves = new AtomicInteger();

  private final AtomicInteger loads = new AtomicInteger();

  @Inject
  RepositoryAggregateRepository repository;

  public int getSaves() {

    return saves.get();

  }

  public int getLoads() {

    return loads.get();

  }

  @Override
  public Class<RepositoryAggregate> getAggregateClass() {

    return RepositoryAggregate.class;

  }

  @Override
  public RepositoryAggregate save(
      final RepositoryAggregate aggregate) {

    saves.incrementAndGet();
    return repository
        .getEntityManager()
        .merge(aggregate);

  }

  @Override
  public RepositoryAggregate loadById(
      final Object aggregateId) {

    loads.incrementAndGet();
    return repository.findById((String) aggregateId);

  }

  @Override
  public Object getAggregateId(
      final RepositoryAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public String getAggregateIdName() {

    return "id";

  }

}
