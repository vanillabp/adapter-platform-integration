package io.vanillabp.integration.runtime.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.inject.spi.CDI;

/**
 * Persistence of aggregates which a Hibernate ORM Panache repository manages
 * ({@code PanacheRepository} or {@code PanacheRepositoryBase}). It is used whenever the
 * application has such a repository for the aggregate and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own.
 * <p>
 * The repository bean is looked up on first use. It is not injected. The subclass generated
 * per aggregate has a no-arg constructor, in bytecode generated at build time. And the
 * repository must not be used before the CDI container is up.
 *
 * @param <A> The aggregate type
 */
public class PanacheRepositoryAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  private final Class<?> repositoryClass;

  private volatile PanacheRepositoryBase<A, Object> repository;

  /**
   * The no-arg constructor of the subclass which the build generates for one aggregate calls
   * it. The repository arrives as a class. It is looked up in the CDI container on first use,
   * because the bean is created before the container is there.
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
