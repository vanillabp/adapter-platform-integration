package io.vanillabp.integration.runtime.persistence;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.inject.spi.CDI;

/**
 * Persistence of aggregates managed by a MongoDB Panache repository
 * ({@code PanacheMongoRepository} or {@code PanacheMongoRepositoryBase}), used
 * whenever the application has such a repository for the aggregate and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own.
 * <p>
 * MongoDB has no session, so storing is <code>persistOrUpdate</code>: the document is
 * inserted or replaced, whichever applies, and the caller keeps its instance.
 *
 * @param <A> The aggregate type
 */
public class PanacheMongoRepositoryAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  private final Class<?> repositoryClass;

  private volatile PanacheMongoRepositoryBase<A, Object> repository;

  public PanacheMongoRepositoryAggregatePersistence(
      final Class<A> aggregateClass,
      final Class<?> repositoryClass) {

    super(aggregateClass);
    this.repositoryClass = repositoryClass;

  }

  @Override
  public A save(
      final A aggregate) {

    repository().persistOrUpdate(aggregate);
    return aggregate;

  }

  @Override
  public A loadById(
      final Object aggregateId) {

    return repository().findById(aggregateId);

  }

  @SuppressWarnings("unchecked")
  private PanacheMongoRepositoryBase<A, Object> repository() {

    if (repository == null) {
      repository = (PanacheMongoRepositoryBase<A, Object>) CDI
          .current()
          .select(repositoryClass)
          .get();
    }
    return repository;

  }

}
