package io.vanillabp.integration.runtime.persistence;

import io.vanillabp.integration.spi.AggregateIdTypes;
import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * Base class of the persistence implementations VanillaBP provides for the
 * persistence idioms of Quarkus (Hibernate ORM Panache, MongoDB Panache and Spring
 * Data). One subclass per aggregate is generated at build time
 * (<code>ProcessServiceBuildStepProcessor</code>) whenever the application does not
 * provide an {@link AggregatePersistenceAware} of its own for that aggregate - an
 * application-provided implementation always wins.
 * <p>
 * Everything about the aggregate's ID is answered by reflection
 * ({@link AggregateIdTypes}) rather than by asking the persistence framework: the
 * ID's name and type are needed at startup and at deployment, where neither a
 * transaction nor a session is guaranteed to be around, and the rules of
 * {@link AggregateIdTypes} (a field or getter annotated with
 * {@code jakarta.persistence.Id}, {@code jakarta.persistence.EmbeddedId} or
 * {@code org.bson.codecs.pojo.annotations.BsonId}, otherwise a member named
 * <code>id</code>) match what JPA and the MongoDB codecs use anyway.
 *
 * @param <A> The aggregate type
 */
public abstract class DefaultAggregatePersistence<A> implements AggregatePersistenceAware<A> {

  protected final Class<A> aggregateClass;

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
