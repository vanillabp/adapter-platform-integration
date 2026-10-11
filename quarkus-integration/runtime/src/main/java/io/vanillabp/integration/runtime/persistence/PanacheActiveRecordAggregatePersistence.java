package io.vanillabp.integration.runtime.persistence;

import io.quarkus.hibernate.orm.panache.Panache;

/**
 * Persistence of aggregates which use the active record pattern of Hibernate ORM Panache.
 * There, the aggregate extends {@code PanacheEntity} or {@code PanacheEntityBase}. It is
 * used whenever the application has no Panache repository and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own for the
 * aggregate.
 * <p>
 * It uses the entity manager of the aggregate's persistence unit directly
 * ({@link Panache#getEntityManager(Class)}). It does not use the static
 * <code>findById</code> and <code>persist</code> methods which Panache generates onto the
 * entity. Those would have to be called by reflection. The entity manager is the documented
 * way to reach the same session.
 *
 * @param <A> The aggregate type
 */
public class PanacheActiveRecordAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  /**
   * The no-arg constructor of the subclass which the build generates for one aggregate calls
   * it.
   *
   * @param aggregateClass The aggregate type, which is also the Panache entity
   */
  public PanacheActiveRecordAggregatePersistence(
      final Class<A> aggregateClass) {

    super(aggregateClass);

  }

  @Override
  public A save(
      final A aggregate) {

    return JpaPersistenceSupport.save(
        aggregate,
        Panache.getEntityManager(aggregateClass),
        this::getAggregateId);

  }

  @Override
  public A loadById(
      final Object aggregateId) {

    return Panache
        .getEntityManager(aggregateClass)
        .find(aggregateClass, aggregateId);

  }

}
