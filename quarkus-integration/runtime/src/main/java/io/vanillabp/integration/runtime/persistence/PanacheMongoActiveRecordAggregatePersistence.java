package io.vanillabp.integration.runtime.persistence;

import io.quarkus.mongodb.panache.runtime.JavaMongoOperations;

/**
 * Persistence of aggregates using the MongoDB Panache active record pattern (the
 * aggregate extends {@code PanacheMongoEntity} or {@code PanacheMongoEntityBase}),
 * used whenever the application has no MongoDB Panache repository and no
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} of its own for the
 * aggregate.
 * <p>
 * Panache's own entity methods are generated as static members onto the entity and
 * would have to be called by reflection; the operations object behind them is public
 * API and is used directly instead.
 *
 * @param <A> The aggregate type
 */
public class PanacheMongoActiveRecordAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  /**
   * Called by the no-arg constructor of the subclass the build generates for one
   * aggregate.
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
