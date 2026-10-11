package io.vanillabp.integration.runtime.processservice;

import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.inject.Instance;

/**
 * Finds out which persistence technology manages a workflow aggregate. Everything which has
 * to write something in the aggregate's OWN transaction asks it: the phase-two outbox
 * ({@link QuarkusPhaseTwoOutboxResolver}), the log of processed task deliveries
 * ({@link QuarkusTaskDeliveryLogResolver}) and the coverage verdict
 * ({@link QuarkusTransactionRunnerResolver}).
 * <p>
 * The counterpart on Spring Boot reads the technology from the aggregate's Spring Data
 * repository. Here it is read from the persistence which VanillaBP resolved for the
 * aggregate. Where the application wrote no {@link AggregatePersistenceAware}, VanillaBP
 * picked one of its own implementations while the application was built. It generated the
 * bean which provides it. That choice names the store. The generated subclasses per
 * aggregate are matched through their superclass.
 * <p>
 * The implementations are matched BY NAME. The MongoDB Panache extension is optional. So
 * this class must not refer to them or to any MongoDB type.
 */
public class QuarkusPersistenceTechnology {

  /**
   * The technologies which VanillaBP brings a store for, plus the answer for everything
   * else. This one answer decides whether the transaction which VanillaBP opens covers an
   * aggregate. It also decides which outbox and delivery log may serve it.
   */
  public enum Technology {
    /** A relational database, reached through JTA - Panache as well as Spring Data. */
    JPA,
    /** MongoDB, where a transaction needs a replica set ({@link MongoDeploymentProbe}). */
    MONGO,
    /** The application brought the persistence itself, so only it knows the store. */
    UNKNOWN
  }

  /** The MongoDB-based aggregate persistence defaults. */
  private static final Set<String> MONGO_DEFAULT_PERSISTENCES = Set
      .of(
          "io.vanillabp.integration.runtime.persistence.PanacheMongoRepositoryAggregatePersistence",
          "io.vanillabp.integration.runtime.persistence.PanacheMongoActiveRecordAggregatePersistence");

  /**
   * The relational defaults. Spring Data on Quarkus means JPA, because the extension knows
   * no other store.
   */
  private static final Set<String> JDBC_DEFAULT_PERSISTENCES = Set
      .of(
          "io.vanillabp.integration.runtime.persistence.PanacheRepositoryAggregatePersistence",
          "io.vanillabp.integration.runtime.persistence.PanacheActiveRecordAggregatePersistence",
          "io.vanillabp.integration.runtime.persistence.SpringDataAggregatePersistence");

  private final Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * Whoever needs the answer creates one. The two store resolvers and the transaction runner
   * resolver each create their own, and all of them read the same beans. The persistences
   * come as an {@link Instance} and not as a list. The beans of an application are resolved
   * when an aggregate is really asked about, not while this object is created.
   *
   * @param aggregatePersistences Every {@link AggregatePersistenceAware} bean of the
   *          application, VanillaBP's generated ones included
   */
  public QuarkusPersistenceTechnology(
      final Instance<AggregatePersistenceAware<?>> aggregatePersistences) {

    this.aggregatePersistences = aggregatePersistences;

  }

  /**
   * The technology which manages an aggregate. It is asked per aggregate class and not once
   * per application. One application may keep one aggregate in a relational database and
   * the next one in MongoDB.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The technology, {@link Technology#UNKNOWN} if the aggregate is stored by a
   *         persistence of the application
   */
  public Technology of(
      final Class<?> workflowAggregateClass) {

    return technologyOf(persistenceOf(workflowAggregateClass));

  }

  /**
   * The persistence which serves an aggregate. It is the most specific
   * {@link AggregatePersistenceAware} which covers its class. That is either an
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
