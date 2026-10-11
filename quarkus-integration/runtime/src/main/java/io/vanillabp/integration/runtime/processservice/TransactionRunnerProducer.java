package io.vanillabp.integration.runtime.processservice;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Produces the transaction of VanillaBP on Quarkus as CDI beans. This is the one place
 * where this application builds them: the platform's own {@link QuarkusTransactionRunner}
 * and the {@link TransactionRunnerResolver}. The resolver says through which runner a
 * workflow aggregate is written.
 * <p>
 * <b>An extension injects the resolver, never a runner.</b> Whatever writes next to a
 * workflow aggregate has to write in the transaction of that aggregate. The extension
 * cannot know which transaction that is. The application may have added a
 * {@link TransactionRunnerAware} bean for the aggregate, or a runner which serves all of
 * them. A runner of the extension's own would commit its entry apart from the workflow it
 * belongs to. So the extension asks {@link TransactionRunnerResolver#resolveFor(Class)} per
 * aggregate class, exactly as the process services do. Where the application added
 * nothing, it gets the platform's own runner. The Spring Boot integration offers the
 * resolver as <code>vanillaBpTransactionRunnerResolver</code> for the same reason. The
 * contract is the same on both platforms.
 * <p>
 * <b>Why {@link Typed}.</b> CDI takes the types of a bean from every supertype of what a
 * producer returns. So this bean would have {@link TransactionRunner} among its types,
 * whatever the return type says. Then an application which adds a runner bean of its own
 * would make every <code>&#64;Inject TransactionRunner</code> ambiguous. This would happen
 * inside the platform and in every extension which thought it could inject one.
 * {@code @Typed(QuarkusTransactionRunner.class)} cuts the bean's types down to that class
 * (plus <code>Object</code>). The platform injects it by its concrete class. An
 * application's runner stays the only bean of the SPI type. And an extension is left with
 * the resolver, which is the answer it wanted anyway.
 */
@ApplicationScoped
public class TransactionRunnerProducer {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * The one runner which both producer methods work with is created later, by
   * {@code buildPlatformRunner}. The reason: the registry it needs is injected first.
   */
  public TransactionRunnerProducer() {
  }

  /**
   * What the application says about which transaction runner serves which aggregate.
   */
  @Inject
  @Any
  Instance<TransactionRunnerAware<?>> transactionRunnerAwares;

  /**
   * Every runner bean of the application. The platform's own is among them, and the
   * resolver tells it apart by identity.
   */
  @Inject
  @Any
  Instance<TransactionRunner> transactionRunners;

  /**
   * The persistences of the application. The coverage verdict is read from them.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * Unsatisfied in an application without the MongoDB client extension. See
   * {@link MongoDeploymentProbe}.
   */
  @Inject
  Instance<MongoDeploymentProbe> mongoDeploymentProbes;

  @Inject
  TransactionSynchronizationRegistry transactionRegistry;

  /**
   * Created once for this producer. So both producer methods hand out the SAME runner. The
   * resolver recognizes the platform's runner by identity. The runner also answers whether
   * the transaction it opened was marked rollback-only, and that belongs to the instance
   * which opened it.
   */
  private QuarkusTransactionRunner platformRunner;

  @PostConstruct
  void buildPlatformRunner() {

    platformRunner = new QuarkusTransactionRunner(transactionRegistry);

  }

  /**
   * The bean is a {@link Singleton} and not in a normal scope on purpose. A pseudo-scoped
   * bean is handed out without a proxy. That lets {@link QuarkusTransactionRunnerResolver}
   * tell the platform's runner from an application's by identity. A normal scope would put a
   * client proxy in front of it, and the comparison would stop matching without a word. So
   * {@code ApplicationOwnedStoresTest} holds the resolution of an application which brought
   * its own runner.
   *
   * @return The platform's runner: JTA, plus the request context which Panache and
   *         Hibernate need on the threads where an adapter delivers
   */
  @Produces
  @Singleton
  @Unremovable
  @Typed(QuarkusTransactionRunner.class)
  public QuarkusTransactionRunner vanillaBpPlatformTransactionRunner() {

    return platformRunner;

  }

  /**
   * Builds the resolver of this application. A new instance per injection point would be a
   * second cache of the same resolutions. So the bean is a {@link Singleton}, like the
   * runner it is built with.
   *
   * @return The resolver, injectable by extensions and used by the process services,
   *         the pre-commit registrar and the startup validation
   */
  @Produces
  @Singleton
  @Unremovable
  public TransactionRunnerResolver vanillaBpTransactionRunnerResolver() {

    return new QuarkusTransactionRunnerResolver(
        transactionRunnerAwares, transactionRunners, aggregatePersistences, mongoDeploymentProbes, platformRunner);

  }

}
