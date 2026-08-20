package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Quarkus implementation of the adapter-facing {@link PreCommitRegistrar} (story 87).
 * <p>
 * It resolves the transaction runner of the workflow aggregate first, so a phase-one check
 * hooks into the unit of work VanillaBP actually uses - which since story 70 may be one the
 * APPLICATION contributed. The resolution is the same one the process services use
 * ({@link QuarkusTransactionRunnerResolver}), built from the same beans, and it caches per
 * aggregate class.
 */
@ApplicationScoped
public class QuarkusPreCommitRegistrar implements PreCommitRegistrar {

  @Inject
  @Any
  Instance<TransactionRunnerAware<?>> transactionRunnerAwares;

  @Inject
  @Any
  Instance<TransactionRunner> applicationTransactionRunners;

  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * Unsatisfied in an application without the MongoDB client extension - see
   * {@link MongoDeploymentProbe}.
   */
  @Inject
  Instance<MongoDeploymentProbe> mongoDeploymentProbes;

  @Inject
  TransactionSynchronizationRegistry transactionRegistry;

  private volatile QuarkusTransactionRunnerResolver resolver;

  private QuarkusTransactionRunnerResolver resolver() {

    if (resolver == null) {
      synchronized (this) {
        if (resolver == null) {
          resolver = new QuarkusTransactionRunnerResolver(
              transactionRunnerAwares, applicationTransactionRunners, aggregatePersistences, mongoDeploymentProbes, new QuarkusTransactionRunner(transactionRegistry));
        }
      }
    }
    return resolver;

  }

  @Override
  public void beforeCommit(
      final Class<?> workflowAggregateClass,
      final Runnable check) {

    resolver()
        .resolveFor(workflowAggregateClass)
        .beforeCommit(check);

  }

}
