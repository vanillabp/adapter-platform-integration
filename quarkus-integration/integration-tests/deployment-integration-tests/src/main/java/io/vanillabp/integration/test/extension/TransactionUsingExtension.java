package io.vanillabp.integration.test.extension;

import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.spi.TransactionRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An extension in miniature. It writes next to a workflow aggregate. So it has to run in
 * the transaction of that aggregate, not in one of its own. It injects the RESOLVER and
 * asks it for each aggregate class, and that is the whole contract. Where the application
 * brings no runner, the answer is the platform's own runner. Injecting a runner directly
 * would be ambiguous as soon as an application brings one.
 */
@ApplicationScoped
public class TransactionUsingExtension {

  @Inject
  TransactionRunnerResolver transactionRunnerResolver;

  /**
   * @param workflowAggregateClass The aggregate whose transaction the write belongs into
   * @return The runner of that aggregate
   */
  public TransactionRunner runnerOf(
      final Class<?> workflowAggregateClass) {

    return transactionRunnerResolver.resolveFor(workflowAggregateClass);

  }

  /**
   * @param workflowAggregateClass The aggregate
   * @return How the platform describes the transaction it resolved for that aggregate
   */
  public String describeResolutionFor(
      final Class<?> workflowAggregateClass) {

    return transactionRunnerResolver.describeResolutionFor(workflowAggregateClass);

  }

}
