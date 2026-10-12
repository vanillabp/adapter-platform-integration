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
 * The Quarkus implementation of the core's {@link TransactionRunnerResolver}.
 * <p>
 * The resolution works like on Spring Boot. First the most specific
 * {@link TransactionRunnerAware} bean, then a plain {@link TransactionRunner} bean of the
 * application, then the platform's own runner. The platform's runner is a bean itself. So
 * the second step leaves it out by identity. Unlike on Spring Boot, the last step always
 * works. <code>quarkus-narayana-jta</code> is a hard dependency of this extension, so a JTA
 * transaction is there even in an application without any data source.
 * <p>
 * That is exactly why the coverage verdict matters here. A transaction exists, but whether
 * it covers the aggregate's store is a different question. MongoDB Panache answers it well.
 * It joins the JTA transaction and starts a MongoDB transaction. So the aggregate is covered
 * as long as the deployment is a replica set. An aggregate persistence of the application is
 * not judged at all.
 * <p>
 * Whether the deployment is a replica set is asked through {@link MongoDeploymentProbe}. That
 * bean exists only where the MongoDB client extension does. Nothing reachable from here names
 * a MongoDB type, and that is a rule, not a detail. A native image resolves every referenced
 * method while it is built. Otherwise an application with a relational database and no
 * MongoDB would not build.
 */
public class QuarkusTransactionRunnerResolver implements TransactionRunnerResolver {

  private final Instance<TransactionRunnerAware<?>> transactionRunnerAwares;

  private final Instance<TransactionRunner> transactionRunners;

  private final QuarkusPersistenceTechnology persistenceTechnology;

  private final Instance<MongoDeploymentProbe> mongoDeploymentProbes;

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
   * A bean of the application together with the name of the class it was DECLARED as.
   *
   * @param <T> The bean type
   */
  private record DeclaredBean<T>(T bean, String declaredClassName) {
  }

  /**
   * {@link TransactionRunnerProducer} creates it, once per application. The platform's runner
   * is passed in. It is not looked up among the runner beans. The second step of the
   * resolution has to tell it from a runner of the application. So the same object has to
   * arrive here which the producer hands out as a bean.
   *
   * @param transactionRunnerAwares What the application says about its aggregates, if anything
   * @param transactionRunners Every runner bean, the platform's own among them
   * @param aggregatePersistences The persistences which an aggregate's technology is read from
   * @param mongoDeploymentProbes The probe, unsatisfied without the MongoDB client extension
   * @param platformRunner The platform's own runner, recognized here by identity
   */
  public QuarkusTransactionRunnerResolver(
      final Instance<TransactionRunnerAware<?>> transactionRunnerAwares,
      final Instance<TransactionRunner> transactionRunners,
      final Instance<AggregatePersistenceAware<?>> aggregatePersistences,
      final Instance<MongoDeploymentProbe> mongoDeploymentProbes,
      final TransactionRunner platformRunner) {

    this.transactionRunnerAwares = transactionRunnerAwares;
    this.transactionRunners = transactionRunners;
    this.persistenceTechnology = new QuarkusPersistenceTechnology(aggregatePersistences);
    this.mongoDeploymentProbes = mongoDeploymentProbes;
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

    // JTA is always there on Quarkus, so this is never the reason why a startup fails.
    // The line exists only to make the core's message complete.
    return "- provide a transaction manager (Quarkus brings JTA, so this should not happen),";

  }

  @Override
  public TransactionCoverage coverageOf(
      final Class<?> workflowAggregateClass) {

    if (resolution(workflowAggregateClass).origin() != Origin.PLATFORM) {
      // The application brought the transaction and knows what it covers.
      return TransactionCoverage.unknown();
    }
    if (persistenceTechnology.of(workflowAggregateClass) != QuarkusPersistenceTechnology.Technology.MONGO) {
      // JPA and Panache take part in the JTA transaction. VanillaBP cannot judge an
      // aggregate persistence of the application.
      return TransactionCoverage.unknown();
    }
    final var replicaSet = mongoDeploymentProbes.isResolvable()
        ? mongoDeploymentProbes
            .get()
            .isReplicaSet()
        : null;
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

    // 2. A plain TransactionRunner bean of the application. VanillaBP's own platform
    // runner is a bean too (TransactionRunnerProducer), but it is not the application's
    // answer. If we took it for one, the coverage of an aggregate would count as the
    // application's business. Then the verdict, which this class exists for, would stay
    // silent.
    final var runners = new LinkedList<>(declaredBeansOf(transactionRunners));
    runners.removeIf(runner -> runner.bean() == platformRunner);
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

    // 3. The platform's runner. It uses JTA, which is always there.
    return new Resolution(platformRunner, Origin.PLATFORM, "the JTA transaction of Quarkus");

  }

  /**
   * The beans of an injection point, each with the class it was DECLARED as. The runtime
   * class of a normal-scoped CDI bean is the client proxy which the container puts in front
   * of it. A name which ends in <code>_ClientProxy</code> sends a reader looking for a class
   * which is not in their sources. The bean metadata of the handle knows the declared class.
   * So a message names what the application wrote.
   * <p>
   * The suffix is NOT removed from a runtime class name, on purpose. That would guess at a
   * naming convention of the container, and it would break on the day the container changes
   * it. Where there is no bean metadata, the runtime class is named. That is still better
   * than nothing.
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
   * The name of the class which a bean was declared as, or else of its runtime class.
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

}
