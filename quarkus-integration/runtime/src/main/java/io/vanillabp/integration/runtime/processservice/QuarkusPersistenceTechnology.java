package io.vanillabp.integration.runtime.processservice;

import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.inject.Instance;

/**
 * Detects which persistence technology manages a workflow aggregate, asked by everything
 * which has to write something in the aggregate's OWN transaction: the phase-two outbox
 * ({@link QuarkusPhaseTwoOutboxResolver}), the log of processed task deliveries
 * ({@link QuarkusTaskDeliveryLogResolver}) and the coverage verdict
 * ({@link QuarkusTransactionRunnerResolver}).
 * <p>
 * The counterpart on Spring Boot reads the technology off the aggregate's Spring Data
 * repository. Here it is read off the persistence VanillaBP resolved for the aggregate:
 * where the application wrote no {@link AggregatePersistenceAware}, VanillaBP picked one of
 * its own implementations while the application was built and generated the bean
 * providing it. That choice names the store, and the generated per-aggregate subclasses are
 * matched through their superclass.
 * <p>
 * The implementations are matched BY NAME: the MongoDB Panache extension is optional, so
 * this class must not carry a reference to them nor to any MongoDB type.
 */
public class QuarkusPersistenceTechnology {

  public enum Technology {
    JPA,
    MONGO,
    /** The application brought the persistence itself, so nobody but it knows the store. */
    UNKNOWN
  }

  /** The MongoDB-based aggregate persistence defaults. */
  private static final Set<String> MONGO_DEFAULT_PERSISTENCES = Set
      .of(
          "io.vanillabp.integration.runtime.persistence.PanacheMongoRepositoryAggregatePersistence",
          "io.vanillabp.integration.runtime.persistence.PanacheMongoActiveRecordAggregatePersistence");

  /**
   * The relational defaults. Spring Data on Quarkus is the JPA one, the
   * extension knows no other store.
   */
  private static final Set<String> JDBC_DEFAULT_PERSISTENCES = Set
      .of(
          "io.vanillabp.integration.runtime.persistence.PanacheRepositoryAggregatePersistence",
          "io.vanillabp.integration.runtime.persistence.PanacheActiveRecordAggregatePersistence",
          "io.vanillabp.integration.runtime.persistence.SpringDataAggregatePersistence");

  private final Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  public QuarkusPersistenceTechnology(
      final Instance<AggregatePersistenceAware<?>> aggregatePersistences) {

    this.aggregatePersistences = aggregatePersistences;

  }

  /**
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The technology, {@link Technology#UNKNOWN} if the aggregate is stored by a
   *         persistence of the application
   */
  public Technology of(
      final Class<?> workflowAggregateClass) {

    return technologyOf(persistenceOf(workflowAggregateClass));

  }

  /**
   * The persistence serving an aggregate: the most specific
   * {@link AggregatePersistenceAware} covering its class, which is either an
   * implementation of the application or the bean generated for one of VanillaBP's own.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The persistence or <code>null</code> if there is none
   */
  public AggregatePersistenceAware<?> persistenceOf(
      final Class<?> workflowAggregateClass) {

    final List<AggregatePersistenceAware<?>> persistences = aggregatePersistences
        .stream()
        .<AggregatePersistenceAware<?>>map(persistence -> persistence)
        .toList();
    return AwareSelection
        .mostSpecific(
            persistences,
            AggregatePersistenceAware::getAggregateClass,
            workflowAggregateClass)
        .orElse(null);

  }

  /**
   * @param persistence The persistence serving an aggregate
   * @return Whether it is one of VanillaBP's MongoDB defaults - the only case in which
   *         the platform knows a MongoDB session is involved
   */
  public static boolean isMongoDefault(
      final AggregatePersistenceAware<?> persistence) {

    return technologyOf(persistence) == Technology.MONGO;

  }

  private static Technology technologyOf(
      final AggregatePersistenceAware<?> persistence) {

    if (persistence == null) {
      return Technology.UNKNOWN;
    }
    for (Class<?> candidate = persistence.getClass(); candidate != null; candidate = candidate
        .getSuperclass()) {
      if (MONGO_DEFAULT_PERSISTENCES.contains(candidate.getName())) {
        return Technology.MONGO;
      }
      if (JDBC_DEFAULT_PERSISTENCES.contains(candidate.getName())) {
        return Technology.JPA;
      }
    }
    return Technology.UNKNOWN;

  }

}
