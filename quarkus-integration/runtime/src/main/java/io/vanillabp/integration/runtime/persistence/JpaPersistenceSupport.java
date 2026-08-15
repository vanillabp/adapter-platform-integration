package io.vanillabp.integration.runtime.persistence;

import java.util.function.Function;

import jakarta.persistence.EntityManager;

/**
 * The JPA half of the persistence defaults: storing an aggregate the way Spring Data
 * does it on the Spring Boot integration, so an application switching platforms sees
 * the same behavior.
 */
final class JpaPersistenceSupport {

  private JpaPersistenceSupport() {
    // utility class
  }

  /**
   * Stores the aggregate:
   * <ol>
   * <li>an aggregate already managed by the session is left alone (the session
   * writes it at flush),</li>
   * <li>an aggregate without an ID is new and gets persisted, so the caller's
   * instance receives a generated ID,</li>
   * <li>anything else is merged, which covers both an aggregate loaded in an earlier
   * transaction and a new aggregate carrying an application-assigned ID (Hibernate
   * inserts it if there is no row yet) - this is what Spring Data's
   * <code>save</code> does, too.</li>
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
