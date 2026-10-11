package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The Quarkus implementation of {@link PreCommitRegistrar}, which adapters use.
 * <p>
 * It first resolves the transaction runner of the workflow aggregate. So a phase-one check
 * hooks into the unit of work which VanillaBP really uses. This may be one which the
 * APPLICATION added. The resolution is the same one which the process services use. It is
 * the bean of {@link TransactionRunnerProducer}, not a resolver of its own, and it caches
 * per aggregate class.
 */
@ApplicationScoped
public class QuarkusPreCommitRegistrar implements PreCommitRegistrar {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * An adapter gets the bean among its collaborators and never creates one. See decision 28
   * in the repository's DECISIONS.md.
   */
  public QuarkusPreCommitRegistrar() {
  }

  @Inject
  TransactionRunnerResolver transactionRunnerResolver;

  @Override
  public void beforeCommit(
      final Class<?> workflowAggregateClass,
      final Runnable check) {

    transactionRunnerResolver
        .resolveFor(workflowAggregateClass)
        .beforeCommit(check);

  }

}
