package io.vanillabp.integration.runtime.persistence;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.inject.spi.CDI;

/**
 * Persistence of aggregates which a MongoDB Panache repository manages
 * ({@code PanacheMongoRepository} or {@code PanacheMongoRepositoryBase}). It is used
 * whenever the application has such a repository for the aggregate and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own.
 * <p>
 * MongoDB has no session, so storing means <code>persistOrUpdate</code>. The document is
 * inserted or replaced, whichever applies. The caller keeps its instance.
 *
 * @param <A> The aggregate type
 */
public class PanacheMongoRepositoryAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  private final Class<?> repositoryClass;

  private volatile PanacheMongoRepositoryBase<A, Object> repository;

  /**
   * The no-arg constructor of the subclass which the build generates for one aggregate calls
   * it. The repository arrives as a class. It is looked up in the CDI container on first use,
   * because the bean is created before the container is there.
   *
   * @param aggregateClass The aggregate type this instance persists
   * @param repositoryClass The repository the application wrote for that aggregate
   */
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
