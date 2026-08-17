package io.vanillabp.integration.processservice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.TransactionCoverage;
import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;

/**
 * Spring Boot implementation of the core's {@link TransactionRunnerResolver} (story 70):
 * which transaction VanillaBP runs the work on a workflow aggregate in, and what that
 * transaction covers.
 * <p>
 * Resolution order:
 * <ol>
 * <li>the most specific {@link TransactionRunnerAware} bean covering the aggregate class
 * (selection via {@link AwareSelection}, ambiguity ends the boot),</li>
 * <li>a plain {@link TransactionRunner} bean of the application, serving every aggregate
 * no aware bean covers,</li>
 * <li>the platform's {@link SpringTransactionRunner}, if a unique
 * <code>PlatformTransactionManager</code> exists,</li>
 * <li>nothing, which the core turns into a guiding startup failure.</li>
 * </ol>
 * The coverage verdict is what makes the difference between "weaker" and "cannot work":
 * a MongoDB-managed aggregate in an application whose only transaction manager is a
 * relational one is written outside every transaction, and the fix has a name
 * (<code>MongoTransactionManager</code>, or a runner of the application). That case ends
 * the boot unless the application accepts it. A MongoDB deployment which is no replica
 * set is reported as well, since a MongoDB transaction cannot even start there.
 */
public class SpringTransactionRunnerResolver implements TransactionRunnerResolver {

  private static final String MONGO_TRANSACTION_MANAGER = "org.springframework.data.mongodb.MongoTransactionManager";

  private static final String MONGO_TEMPLATE = "org.springframework.data.mongodb.core.MongoTemplate";

  private final ApplicationContext applicationContext;

  private final SpringTransactionRunner platformRunner;

  private final java.util.function.Function<Class<?>, SpringPersistenceTechnology.Technology> persistenceTechnology;

  private final Map<Class<?>, Resolution> resolutions = new ConcurrentHashMap<>();

  private enum Origin {
    AWARE,
    APPLICATION_BEAN,
    PLATFORM,
    NONE
  }

  private record Resolution(TransactionRunner runner, Origin origin, String description) {
  }

  public SpringTransactionRunnerResolver(
      final ApplicationContext applicationContext,
      final SpringTransactionRunner platformRunner) {

    this(applicationContext, platformRunner, new SpringPersistenceTechnology(applicationContext)::of);

  }

  /**
   * Takes the detection of an aggregate's persistence technology as a function - the shape
   * the tests of the coverage verdicts use, since building a real Spring Data repository
   * infrastructure would say nothing more about them.
   *
   * @param applicationContext Used to look up runner and aware beans
   * @param platformRunner The platform's runner, the last resolution step
   * @param persistenceTechnology Which technology manages an aggregate
   */
  SpringTransactionRunnerResolver(
      final ApplicationContext applicationContext,
      final SpringTransactionRunner platformRunner,
      final java.util.function.Function<Class<?>, SpringPersistenceTechnology.Technology> persistenceTechnology) {

    this.applicationContext = applicationContext;
    this.platformRunner = platformRunner;
    this.persistenceTechnology = persistenceTechnology;

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

    final var managers = applicationContext.getBeanNamesForType(
        org.springframework.transaction.PlatformTransactionManager.class);
    if (managers.length > 1) {
      // the mixed-persistence case: managers exist, but none of them is THE one
      return """
          - this application has several transaction managers (%s), so none of them is the one to \
          use: attribute your workflow aggregates to them by defining \
          io.vanillabp.integration.spi.TransactionRunnerAware beans, each returning \
          'new io.vanillabp.integration.workflowtask.SpringTransactionRunner(theMatchingManager)',"""
          .formatted(String.join(", ", managers));
    }
    return """
        - define a transaction manager covering the persistence of your workflow aggregates: a JPA \
        or JDBC one (e.g. spring-boot-starter-data-jpa with a data source), or a \
        MongoTransactionManager if they live in MongoDB (which needs the deployment to be a \
        replica set),""";

  }

  @Override
  public TransactionCoverage coverageOf(
      final Class<?> workflowAggregateClass) {

    final var resolution = resolution(workflowAggregateClass);
    if ((resolution.origin() == Origin.AWARE) || (resolution.origin() == Origin.APPLICATION_BEAN)) {
      // the application brought the transaction, so the application knows what it
      // covers - VanillaBP has nothing to add and says nothing
      return TransactionCoverage.unknown();
    }
    if (resolution.origin() == Origin.NONE) {
      return TransactionCoverage.unknown();
    }

    final var technology = persistenceTechnology.apply(workflowAggregateClass);
    final var manager = platformRunner.getTransactionManager();
    final var managerCoversMongo = (manager != null) && isOrExtends(manager.getClass(), MONGO_TRANSACTION_MANAGER);

    return switch (technology) {
      case MONGO -> managerCoversMongo
          ? replicaSetCoverage(workflowAggregateClass)
          : TransactionCoverage.uncoverable(mongoAggregateWithoutMongoManager(workflowAggregateClass, manager));
      case JPA -> managerCoversMongo
          ? TransactionCoverage.uncoverable(jpaAggregateWithMongoManagerOnly(workflowAggregateClass))
          : TransactionCoverage.covered();
      case UNKNOWN -> TransactionCoverage.unknown();
    };

  }

  /**
   * A MongoDB-managed aggregate with a MongoDB transaction manager is covered - as long
   * as the deployment is a replica set, because a MongoDB transaction needs one.
   */
  private TransactionCoverage replicaSetCoverage(
      final Class<?> workflowAggregateClass) {

    if (!ClassUtils.isPresent(MONGO_TEMPLATE, getClass().getClassLoader())) {
      return TransactionCoverage.covered();
    }
    final var replicaSet = SpringMongoDeployment.isReplicaSet(applicationContext);
    if (!Boolean.FALSE.equals(replicaSet)) {
      // true, or a question which could not be answered: a probe must not produce a
      // verdict it is not sure about
      return TransactionCoverage.covered();
    }
    return TransactionCoverage.unguarded(
        """
            The workflow aggregate '%s' is managed by MongoDB and a MongoTransactionManager is \
            configured, but the MongoDB deployment is not a replica set - MongoDB transactions are \
            only available on a replica set or a sharded cluster, so every write will fail with \
            'Transaction numbers are only allowed on a replica set member or mongos'. Either run \
            MongoDB as a replica set (a single-node replica set is enough), or remove the \
            MongoTransactionManager and accept that the aggregate, the phase-two outbox entry and \
            the delivery record of one workflow step no longer commit together."""
            .formatted(workflowAggregateClass.getName()));

  }

  private String mongoAggregateWithoutMongoManager(
      final Class<?> workflowAggregateClass,
      final org.springframework.transaction.PlatformTransactionManager manager) {

    return """
        The workflow aggregate '%s' is managed by MongoDB, but the only transaction manager of this \
        application is '%s' - it does not cover MongoDB. VanillaBP would load the aggregate, run \
        the @WorkflowTask method, save the aggregate, write the delivery record and schedule the \
        phase-two outbox entry with nothing holding them together: a rollback would leave the \
        aggregate written, and a crash between two of those writes leaves the workflow and its \
        data disagreeing. To solve this either
        - define a MongoTransactionManager bean (which needs the MongoDB deployment to be a \
        replica set; a single-node replica set is enough), or
        - define a bean implementing io.vanillabp.integration.spi.TransactionRunnerAware for this \
        aggregate, providing a runner which covers its store.
        Note that an embedded Camunda 7 needs a relational database, so its transaction can never \
        cover a MongoDB-managed aggregate - an application combining the two accepts that the \
        engine and the aggregate commit separately."""
        .formatted(
            workflowAggregateClass.getName(),
            manager == null
                ? "none"
                : manager
                    .getClass()
                    .getName());

  }

  private String jpaAggregateWithMongoManagerOnly(
      final Class<?> workflowAggregateClass) {

    return """
        The workflow aggregate '%s' is managed by JPA, but the only transaction manager of this \
        application is a MongoTransactionManager - it does not cover the relational database. \
        Nothing VanillaBP writes for one workflow step would commit together. To solve this either \
        define a JPA or JDBC transaction manager for it (and attribute the aggregates to their \
        managers by contributing io.vanillabp.integration.spi.TransactionRunnerAware beans), or \
        provide a TransactionRunnerAware bean for this aggregate."""
        .formatted(workflowAggregateClass.getName());

  }

  private Resolution resolution(
      final Class<?> workflowAggregateClass) {

    return resolutions.computeIfAbsent(workflowAggregateClass, this::resolve);

  }

  private Resolution resolve(
      final Class<?> workflowAggregateClass) {

    // 1. the most specific TransactionRunnerAware bean covering the aggregate class
    final var awares = applicationContext.getBeansOfType(TransactionRunnerAware.class);
    final var mostSpecificAware = AwareSelection
        .mostSpecificDistinct(
            awares.entrySet(),
            aware -> ((TransactionRunnerAware<?>) aware.getValue()).getAggregateClass(),
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
                            .map(Map.Entry::getKey)
                            .toList(),
                        workflowAggregateClass.getName())));
    if (mostSpecificAware.isPresent()) {
      final var aware = mostSpecificAware.get();
      return new Resolution(
          ((TransactionRunnerAware<?>) aware.getValue())
              .getTransactionRunner(), Origin.AWARE, "the TransactionRunnerAware bean '%s' of the application"
                  .formatted(aware.getKey()));
    }

    // 2. a plain TransactionRunner bean of the application - VanillaBP's own platform
    // runner is a bean as well, and it is not the application's answer
    final var runners = new java.util.LinkedHashMap<>(
        applicationContext.getBeansOfType(TransactionRunner.class));
    runners
        .values()
        .removeIf(runner -> runner == platformRunner);
    if (runners.size() == 1) {
      final var runner = runners
          .entrySet()
          .iterator()
          .next();
      return new Resolution(
          runner.getValue(), Origin.APPLICATION_BEAN, "the TransactionRunner bean '%s' of the application"
              .formatted(runner.getKey()));
    }
    if (runners.size() > 1) {
      throw new IllegalStateException(
          """
              Several TransactionRunner beans exist (%s) and none of them names the workflow \
              aggregate '%s'! A runner serving every aggregate has to be the only one - attribute \
              them to their aggregates by contributing \
              io.vanillabp.integration.spi.TransactionRunnerAware beans instead."""
              .formatted(runners.keySet(), workflowAggregateClass.getName()));
    }

    // 3. the platform's runner, if it can open a transaction at all
    if (platformRunner.isUsable()) {
      return new Resolution(
          platformRunner, Origin.PLATFORM, "the transaction manager of the application ('%s')".formatted(
              platformRunner
                  .getTransactionManager()
                  .getClass()
                  .getName()));
    }

    // 4. nothing usable
    return new Resolution(null, Origin.NONE, "nothing - no transaction is available");

  }

  private static boolean isOrExtends(
      final Class<?> type,
      final String className) {

    for (var candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
      if (candidate
          .getName()
          .equals(className)) {
        return true;
      }
    }
    return false;

  }

}
