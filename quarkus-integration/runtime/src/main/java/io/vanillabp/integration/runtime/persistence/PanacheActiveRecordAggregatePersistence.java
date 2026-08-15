package io.vanillabp.integration.runtime.persistence;

import io.quarkus.hibernate.orm.panache.Panache;

/**
 * Persistence of aggregates using the Hibernate ORM Panache active record pattern
 * (the aggregate extends {@code PanacheEntity} or {@code PanacheEntityBase}), used
 * whenever the application has no Panache repository and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own for the
 * aggregate.
 * <p>
 * The entity manager of the aggregate's persistence unit is used directly
 * ({@link Panache#getEntityManager(Class)}) instead of the static
 * <code>findById</code>/<code>persist</code> methods Panache generates onto the
 * entity: those would have to be called by reflection, and the entity manager is the
 * documented way to reach the same session.
 *
 * @param <A> The aggregate type
 */
public class PanacheActiveRecordAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

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
