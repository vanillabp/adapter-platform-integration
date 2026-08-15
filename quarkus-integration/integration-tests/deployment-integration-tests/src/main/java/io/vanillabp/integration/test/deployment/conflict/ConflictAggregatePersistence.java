package io.vanillabp.integration.test.deployment.conflict;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * JPA persistence of the version-conflict acceptance test's aggregate - the real
 * thing, since the point of the test is what Hibernate does when the version of a row
 * moved on while VanillaBP's transaction was open.
 */
@ApplicationScoped
public class ConflictAggregatePersistence implements AggregatePersistenceAware<ConflictAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<ConflictAggregate> getAggregateClass() {

    return ConflictAggregate.class;

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public Object getAggregateId(
      final ConflictAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public ConflictAggregate save(
      final ConflictAggregate aggregate) {

    return entityManager.merge(aggregate);

  }

  @Override
  public ConflictAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(ConflictAggregate.class, aggregateId);

  }

  /**
   * Stores an aggregate as if a workflow had been started, in a transaction of its
   * own.
   *
   * @param id The aggregate's ID
   */
  @Transactional
  public void seed(
      final String id) {

    final var aggregate = new ConflictAggregate();
    aggregate.setId(id);
    aggregate.setContent("new");
    entityManager.persist(aggregate);

  }

  /**
   * The content stored for the given aggregate, read in a transaction of its own.
   *
   * @param id The aggregate's ID
   * @return The content committed to the database
   */
  @Transactional
  public String storedContent(
      final String id) {

    final var aggregate = entityManager.find(ConflictAggregate.class, id);
    return aggregate == null
        ? null
        : aggregate.getContent();

  }

}
