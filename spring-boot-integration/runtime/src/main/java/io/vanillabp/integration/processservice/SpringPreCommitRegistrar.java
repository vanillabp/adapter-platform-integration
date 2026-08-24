package io.vanillabp.integration.processservice;

import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;

/**
 * Spring Boot implementation of the adapter-facing {@link PreCommitRegistrar}.
 * <p>
 * It resolves the transaction runner of the workflow aggregate first, so a phase-one check
 * hooks into the unit of work VanillaBP actually uses - which may be one the
 * APPLICATION contributed. Asking the platform's runner instead would register a
 * synchronization on a transaction the aggregate is not stored in.
 */
public class SpringPreCommitRegistrar implements PreCommitRegistrar {

  private final SpringTransactionRunnerResolver transactionRunnerResolver;

  public SpringPreCommitRegistrar(
      final SpringTransactionRunnerResolver transactionRunnerResolver) {

    this.transactionRunnerResolver = transactionRunnerResolver;

  }

  @Override
  public void beforeCommit(
      final Class<?> workflowAggregateClass,
      final Runnable check) {

    transactionRunnerResolver
        .resolveFor(workflowAggregateClass)
        .beforeCommit(check);

  }

}
