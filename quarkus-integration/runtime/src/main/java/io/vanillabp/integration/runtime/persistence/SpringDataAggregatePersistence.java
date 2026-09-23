package io.vanillabp.integration.runtime.persistence;

import org.springframework.data.repository.CrudRepository;

import jakarta.enterprise.inject.spi.CDI;

/**
 * Persistence of aggregates managed by a Spring Data repository (extension
 * {@code quarkus-spring-data-jpa}), used whenever the application has a
 * {@code CrudRepository} for the aggregate, no Panache repository, no Panache active
 * record and no {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its
 * own.
 * <p>
 * This is the same repository API the Spring Boot integration uses, so an application
 * moving between the two platforms keeps the behavior of <code>save</code>.
 *
 * @param <A> The aggregate type
 */
public class SpringDataAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  private final Class<?> repositoryClass;

  private volatile CrudRepository<A, Object> repository;

  /**
   * Called by the no-arg constructor of the subclass the build generates for one
   * aggregate. The repository arrives as a class and is looked up in the CDI container
   * on first use, because the bean is built before the container is there.
   *
   * @param aggregateClass The aggregate type this instance persists
   * @param repositoryClass The <code>CrudRepository</code> the application wrote for
   *          that aggregate
   */
  public SpringDataAggregatePersistence(
      final Class<A> aggregateClass,
      final Class<?> repositoryClass) {

    super(aggregateClass);
    this.repositoryClass = repositoryClass;

  }

  @Override
  public A save(
      final A aggregate) {

    return repository().save(aggregate);

  }

  @Override
  public A loadById(
      final Object aggregateId) {

    return repository()
        .findById(aggregateId)
        .orElse(null);

  }

  @SuppressWarnings("unchecked")
  private CrudRepository<A, Object> repository() {

    if (repository == null) {
      repository = (CrudRepository<A, Object>) CDI
          .current()
          .select(repositoryClass)
          .get();
    }
    return repository;

  }

}
