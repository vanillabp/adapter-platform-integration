package io.vanillabp.integration.runtime.persistence;

import java.util.function.Function;

import jakarta.persistence.EntityManager;

/**
 * The JPA half of the persistence defaults. It stores an aggregate the way Spring Data does
 * it on the Spring Boot integration. So an application which switches platforms sees the
 * same behavior.
 */
final class JpaPersistenceSupport {

  private JpaPersistenceSupport() {
  }

  /**
   * Stores the aggregate:
   * <ol>
   * <li>An aggregate which the session already manages is left alone. The session writes
   * it at flush.</li>
   * <li>An aggregate without an ID is new and is persisted. So the caller's instance gets a
   * generated ID.</li>
   * <li>Anything else is merged. This covers an aggregate loaded in an earlier transaction.
   * It also covers a new aggregate with an ID set by the application. Hibernate inserts it if
   * there is no row yet. Spring Data's <code>save</code> does the same.</li>
   * </ol>
   *
   * @param aggregate The aggregate to store
   * @param entityManager The entity manager of the aggregate's persistence unit
   * @param idOf Reads the aggregate's ID
   * @return The stored aggregate, attached to the session
   */
  static <A> A save(
      final A aggregate,
      final EntityManager entityManager,
      final Function<A, Object> idOf) {

    if (entityManager.contains(aggregate)) {
      return aggregate;
    }
    if (idOf.apply(aggregate) == null) {
      entityManager.persist(aggregate);
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

}
