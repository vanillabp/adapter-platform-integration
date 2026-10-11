package io.vanillabp.integration.runtime.persistence;

import org.springframework.data.repository.CrudRepository;

import jakarta.enterprise.inject.spi.CDI;

/**
 * Persistence of aggregates which a Spring Data repository manages (extension
 * {@code quarkus-spring-data-jpa}). It is used whenever the application has a
 * {@code CrudRepository} for the aggregate, but no Panache repository, no Panache active
 * record and no {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its
 * own.
 * <p>
 * The Spring Boot integration uses the same repository API. So an application which moves
 * between the two platforms keeps the behavior of <code>save</code>.
 *
 * @param <A> The aggregate type
 */
public class SpringDataAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  private final Class<?> repositoryClass;

  private volatile CrudRepository<A, Object> repository;

  /**
   * The no-arg constructor of the subclass which the build generates for one aggregate calls
   * it. The repository arrives as a class. It is looked up in the CDI container on first use,
   * because the bean is created before the container is there.
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
