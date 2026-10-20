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
 * <p>
 * An aggregate which was loaded in the running transaction and did not change since is not
 * written again (see {@link DocumentsAtLoad}). Each write would replace the document, and two
 * parallel tasks which only read the aggregate would collide.
 *
 * @param <A> The aggregate type
 */
public class PanacheMongoActiveRecordAggregatePersistence<A> extends DefaultAggregatePersistence<A> {

  private final DocumentsAtLoad documentsAtLoad = new DocumentsAtLoad();

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

  /**
   * Writes the aggregate, unless it was loaded in the running transaction and its document
   * did not change since.
   */
  @Override
  public A save(
      final A aggregate) {

    final var collection = JavaMongoOperations.INSTANCE.mongoCollection(aggregateClass);
    if (documentsAtLoad.isUnchanged(collection, aggregate)) {
      return aggregate;
    }
    JavaMongoOperations.INSTANCE.persistOrUpdate(aggregate);
    documentsAtLoad.remember(collection, aggregate);
    return aggregate;

  }

  /**
   * Loads the aggregate and remembers its document for the running transaction.
   */
  @Override
  @SuppressWarnings("unchecked")
  public A loadById(
      final Object aggregateId) {

    final var aggregate = (A) JavaMongoOperations.INSTANCE.findById(aggregateClass, aggregateId);
    if (aggregate != null) {
      documentsAtLoad.remember(JavaMongoOperations.INSTANCE.mongoCollection(aggregateClass), aggregate);
    }
    return aggregate;

  }

}
