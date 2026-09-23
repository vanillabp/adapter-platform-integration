package io.vanillabp.integration.runtime.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.inject.spi.CDI;

/**
 * Persistence of aggregates managed by a Hibernate ORM Panache repository
 * ({@code PanacheRepository} or {@code PanacheRepositoryBase}), used whenever the
 * application has such a repository for the aggregate and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own.
 * <p>
 * The repository bean is resolved on first use instead of being injected: the
 * subclass generated per aggregate has a no-arg constructor (build-time generated
 * bytecode) and the repository must not be touched before the CDI container is up.
 *
 * @param <A> The aggregate type
 */
public class PanacheRepositoryAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  private final Class<?> repositoryClass;

  private volatile PanacheRepositoryBase<A, Object> repository;

  /**
   * Called by the no-arg constructor of the subclass the build generates for one
   * aggregate. The repository arrives as a class and is looked up in the CDI container
   * on first use, because the bean is built before the container is there.
   *
   * @param aggregateClass The aggregate type this instance persists
   * @param repositoryClass The repository the application wrote for that aggregate
   */
  public PanacheRepositoryAggregatePersistence(
      final Class<A> aggregateClass,
      final Class<?> repositoryClass) {

    super(aggregateClass);
    this.repositoryClass = repositoryClass;

  }

  @Override
  public A save(
      final A aggregate) {

    final var entityManager = repository().getEntityManager();
    return JpaPersistenceSupport.save(aggregate, entityManager, this::getAggregateId);

  }

  @Override
  public A loadById(
      final Object aggregateId) {

    return repository().findById(aggregateId);

  }

  @SuppressWarnings("unchecked")
  private PanacheRepositoryBase<A, Object> repository() {

    if (repository == null) {
      repository = (PanacheRepositoryBase<A, Object>) CDI
          .current()
          .select(repositoryClass)
          .get();
    }
    return repository;

  }

}
