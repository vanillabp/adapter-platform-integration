package io.vanillabp.integration.runtime.persistence;

import io.quarkus.mongodb.panache.runtime.JavaMongoOperations;

/**
 * Persistence of aggregates which use the active record pattern of MongoDB Panache. There,
 * the aggregate extends {@code PanacheMongoEntity} or {@code PanacheMongoEntityBase}. It is
 * used whenever the application has no MongoDB Panache repository and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own for the
 * aggregate.
 * <p>
 * Panache generates its own entity methods as static members onto the entity. Those would
 * have to be called by reflection. The operations object behind them is public API, so this
 * class uses it directly.
 *
 * @param <A> The aggregate type
 */
public class PanacheMongoActiveRecordAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  /**
   * The no-arg constructor of the subclass which the build generates for one aggregate calls
   * it.
   *
   * @param aggregateClass The aggregate type, which is also the MongoDB Panache entity
   */
  public PanacheMongoActiveRecordAggregatePersistence(
      final Class<A> aggregateClass) {

    super(aggregateClass);

  }

  @Override
  public A save(
      final A aggregate) {

    JavaMongoOperations.INSTANCE.persistOrUpdate(aggregate);
    return aggregate;

  }

  @Override
  @SuppressWarnings("unchecked")
  public A loadById(
      final Object aggregateId) {

    return (A) JavaMongoOperations.INSTANCE.findById(aggregateClass, aggregateId);

  }

}
