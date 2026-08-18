package io.vanillabp.integration.runtime.processservice;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.TransactionCoverage;
import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Instance.Handle;

/**
 * Quarkus implementation of the core's {@link TransactionRunnerResolver} (story 70).
 * <p>
 * Resolution mirrors Spring Boot: the most specific {@link TransactionRunnerAware} bean,
 * then a plain {@link TransactionRunner} bean of the application, then the platform's own
 * runner. Unlike Spring Boot the last step always works, because
 * <code>quarkus-narayana-jta</code> is a hard dependency of this extension - a JTA
 * transaction is available even in an application without any data source.
 * <p>
 * Which is exactly why the coverage verdict matters here: a transaction exists, and
 * whether it covers the aggregate's store is a different question. MongoDB Panache
 * answers it well - it enlists itself in the JTA transaction and starts a MongoDB
 * transaction, so the aggregate is covered as long as the deployment is a replica set.
 * An aggregate persistence of the application is not judged at all.
 */
public class QuarkusTransactionRunnerResolver implements TransactionRunnerResolver {

  private final Instance<TransactionRunnerAware<?>> transactionRunnerAwares;

  private final Instance<TransactionRunner> transactionRunners;

  private final Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  private final TransactionRunner platformRunner;

  private final Map<Class<?>, Resolution> resolutions = new ConcurrentHashMap<>();

  private enum Origin {
    AWARE,
    APPLICATION_BEAN,
    PLATFORM
  }

  private record Resolution(TransactionRunner runner, Origin origin, String description) {
  }

  /**
   * A bean of the application together with the name of the class it was DECLARED as
   * (story 80).
   *
   * @param <T> The bean type
   */
  private record DeclaredBean<T>(T bean, String declaredClassName) {
  }

  public QuarkusTransactionRunnerResolver(
      final Instance<TransactionRunnerAware<?>> transactionRunnerAwares,
      final Instance<TransactionRunner> transactionRunners,
      final Instance<AggregatePersistenceAware<?>> aggregatePersistences,
      final TransactionRunner platformRunner) {

    this.transactionRunnerAwares = transactionRunnerAwares;
    this.transactionRunners = transactionRunners;
    this.aggregatePersistences = aggregatePersistences;
    this.platformRunner = platformRunner;

  }

  @Override
  public TransactionRunner resolveFor(
      final Class<?> workflowAggregateClass) {

    return resolution(workflowAggregateClass).runner();

  }

  @Override
  public String describeResolutionFor(
      final Class<?> workflowAggregateClass) {

    return resolution(workflowAggregateClass).description();

  }

  @Override
  public String remediesDescription() {

    // JTA is always available on Quarkus, so this is never the reason a startup fails -
    // the line exists for completeness of the core's message
    return "- provide a transaction manager (Quarkus brings JTA, so this should not happen),";

  }

  @Override
  public TransactionCoverage coverageOf(
      final Class<?> workflowAggregateClass) {

    if (resolution(workflowAggregateClass).origin() != Origin.PLATFORM) {
      // the application brought the transaction and knows what it covers
      return TransactionCoverage.unknown();
    }
    final var persistence = aggregatePersistenceOf(workflowAggregateClass);
    if (!isMongoDefault(persistence)) {
      // JPA/Panache take part in the JTA transaction; an aggregate persistence of the
      // application is not something VanillaBP can judge
      return TransactionCoverage.unknown();
    }
    final var replicaSet = QuarkusMongoDeployment.isReplicaSet();
    if (!Boolean.FALSE.equals(replicaSet)) {
      return TransactionCoverage.covered();
    }
    return TransactionCoverage.unguarded(
        """
            The workflow aggregate '%s' is stored in MongoDB, but the MongoDB deployment is not a \
            replica set - MongoDB transactions are only available on a replica set or a sharded \
            cluster. MongoDB Panache starts a MongoDB transaction whenever it writes inside the \
            transaction VanillaBP opens, so writing this aggregate will fail with 'Transaction \
            numbers are only allowed on a replica set member or mongos'. Run MongoDB as a replica \
            set (a single-node replica set is enough)."""
            .formatted(workflowAggregateClass.getName()));

  }

  private Resolution resolution(
      final Class<?> workflowAggregateClass) {

    return resolutions.computeIfAbsent(workflowAggregateClass, this::resolve);

  }

  private Resolution resolve(
      final Class<?> workflowAggregateClass) {

    // 1. the most specific TransactionRunnerAware bean covering the aggregate class
    final var awares = declaredBeansOf(transactionRunnerAwares);
    final var mostSpecificAware = AwareSelection
        .mostSpecificDistinct(
            awares,
            aware -> aware
                .bean()
                .getAggregateClass(),
            workflowAggregateClass,
            tied -> new IllegalStateException(
                """
                    The TransactionRunnerAware beans %s all cover the workflow aggregate '%s' at the \
                    same distance, so which transaction VanillaBP would use depends on the order the \
                    beans were found in! Name the aggregate itself in one of them, or remove one - a \
                    transaction boundary must not be decided by chance."""
                    .formatted(
                        tied
                            .stream()
                            .map(DeclaredBean::declaredClassName)
                            .toList(),
                        workflowAggregateClass.getName())));
    if (mostSpecificAware.isPresent()) {
      final var aware = mostSpecificAware.get();
      return new Resolution(
          aware
              .bean()
              .getTransactionRunner(), Origin.AWARE, "the TransactionRunnerAware bean '%s' of the application"
                  .formatted(aware.declaredClassName()));
    }

    // 2. a plain TransactionRunner bean of the application
    final var runners = declaredBeansOf(transactionRunners);
    if (runners.size() == 1) {
      return new Resolution(
          runners
              .getFirst()
              .bean(), Origin.APPLICATION_BEAN, "the TransactionRunner bean '%s' of the application"
                  .formatted(
                      runners
                          .getFirst()
                          .declaredClassName()));
    }
    if (runners.size() > 1) {
      throw new IllegalStateException(
          """
              Several TransactionRunner beans exist (%s) and none of them names the workflow \
              aggregate '%s'! A runner serving every aggregate has to be the only one - attribute \
              them to their aggregates by contributing \
              io.vanillabp.integration.spi.TransactionRunnerAware beans instead."""
              .formatted(
                  runners
                      .stream()
                      .map(DeclaredBean::declaredClassName)
                      .toList(),
                  workflowAggregateClass.getName()));
    }

    // 3. the platform's runner - JTA, always available
    return new Resolution(platformRunner, Origin.PLATFORM, "the JTA transaction of Quarkus");

  }

  /**
   * The beans of an injection point together with the class each of them was DECLARED
   * as (story 80): the runtime class of a normal-scoped CDI bean is the client proxy
   * the container puts in front of it, and a name ending in <code>_ClientProxy</code>
   * sends a reader looking for a class which is not in their sources. The bean
   * metadata of the handle knows the declared class, so a message names what the
   * application wrote.
   * <p>
   * The suffix is deliberately NOT stripped from a runtime class name: that would
   * guess at a naming convention of the container and break the day the container
   * changes it. Where no bean metadata is available the runtime class is named, which
   * is still better than nothing.
   *
   * @param <T> The bean type
   * @param instance The injection point
   * @return The beans and their declared class names
   */
  private static <T> List<DeclaredBean<T>> declaredBeansOf(
      final Instance<T> instance) {

    final var result = new LinkedList<DeclaredBean<T>>();
    for (final Handle<T> handle : instance.handles()) {
      final var bean = handle.get();
      if (bean == null) {
        continue;
      }
      result.add(new DeclaredBean<>(bean, declaredClassNameOf(handle, bean)));
    }
    return result;

  }

  /**
   * The name of the class a bean was declared as, falling back to the runtime class.
   *
   * @param handle The bean's handle
   * @param bean The bean
   * @return The class name to name in a message
   */
  private static String declaredClassNameOf(
      final Handle<?> handle,
      final Object bean) {

    final var metadata = handle.getBean();
    final var declaredClass = metadata == null
        ? null
        : metadata.getBeanClass();
    return declaredClass == null
        ? bean
            .getClass()
            .getName()
        : declaredClass.getName();

  }

  private AggregatePersistenceAware<?> aggregatePersistenceOf(
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
   * The MongoDB-based aggregate persistence defaults of story 69, matched BY NAME: the
   * MongoDB Panache extension is optional here, so this class must not carry a reference
   * to them (nor to any MongoDB type).
   */
  private static final java.util.Set<String> MONGO_DEFAULT_PERSISTENCES = java.util.Set
      .of(
          "io.vanillabp.integration.runtime.persistence.PanacheMongoRepositoryAggregatePersistence",
          "io.vanillabp.integration.runtime.persistence.PanacheMongoActiveRecordAggregatePersistence");

  /**
   * Whether the aggregate is stored by one of VanillaBP's MongoDB defaults (story 69) -
   * the only case in which the platform knows a MongoDB session is involved. The
   * generated per-aggregate subclasses are matched through their superclass.
   */
  private static boolean isMongoDefault(
      final AggregatePersistenceAware<?> persistence) {

    if (persistence == null) {
      return false;
    }
    for (Class<?> candidate = persistence.getClass(); candidate != null; candidate = candidate
        .getSuperclass()) {
      if (MONGO_DEFAULT_PERSISTENCES.contains(candidate.getName())) {
        return true;
      }
    }
    return false;

  }

}
