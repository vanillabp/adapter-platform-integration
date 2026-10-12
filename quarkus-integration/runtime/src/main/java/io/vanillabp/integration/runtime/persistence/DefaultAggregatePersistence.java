package io.vanillabp.integration.runtime.persistence;

import io.vanillabp.integration.spi.AggregateIdTypes;
import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * Base class of the persistence implementations which VanillaBP provides for the usual ways
 * to persist on Quarkus: Hibernate ORM Panache, MongoDB Panache and Spring Data. The build
 * generates one subclass per aggregate (<code>ProcessServiceBuildStepProcessor</code>). It
 * does so whenever the application has no {@link AggregatePersistenceAware} of its own for
 * that aggregate. An implementation of the application always wins.
 * <p>
 * Everything about the aggregate's ID is answered by reflection ({@link AggregateIdTypes}).
 * The persistence framework is not asked. The name and type of the ID are needed at startup
 * and at deployment. There, a transaction or a session may be missing. The rules of
 * {@link AggregateIdTypes} match what JPA and the MongoDB codecs use anyway: a field or
 * getter annotated with {@code jakarta.persistence.Id},
 * {@code jakarta.persistence.EmbeddedId} or {@code org.bson.codecs.pojo.annotations.BsonId},
 * and otherwise a member named <code>id</code>.
 *
 * @param <A> The aggregate type
 */
public abstract class DefaultAggregatePersistence<A> implements AggregatePersistenceAware<A> {

  /**
   * The aggregate type which this instance serves. There is one instance per aggregate. So the
   * class is known when the bean is created, and every question about the aggregate's ID is
   * answered against it.
   */
  protected final Class<A> aggregateClass;

  /**
   * The subclass which the build generates for one aggregate calls it and passes the class
   * literal. Nothing of the persistence framework is used here. The bean is created before
   * the CDI container serves anybody.
   *
   * @param aggregateClass The aggregate type this instance persists
   */
  protected DefaultAggregatePersistence(
      final Class<A> aggregateClass) {

    this.aggregateClass = aggregateClass;

  }

  @Override
  public Class<A> getAggregateClass() {

    return aggregateClass;

  }

  @Override
  public Object getAggregateId(
      final A aggregate) {

    return AggregateIdTypes.readId(aggregate);

  }

  @Override
  public String getAggregateIdName() {

    return AggregateIdTypes
        .determineIdName(aggregateClass)
        .orElseThrow(() -> new IllegalStateException(
            """
                No ID property found for workflow aggregate '%s'! The configured VanillaBP \
                adapter stores the aggregate's ID in the BPMS named like the aggregate's ID \
                property. Annotate the property (e.g. jakarta.persistence.Id or \
                org.bson.codecs.pojo.annotations.BsonId), name it 'id', or provide your own \
                io.vanillabp.integration.spi.AggregatePersistenceAware implementation for this \
                aggregate."""
                .formatted(aggregateClass.getName())));

  }

}
