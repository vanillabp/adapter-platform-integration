package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;

/**
 * Resolves the {@link TransactionRunner} used for a workflow aggregate, implemented by
 * the platform integrations and invoked by the core at startup (see
 * {@link MigrationProcessService#validateTransactionRunnerAtStartup()}) as well as
 * lazily for every operation touching that aggregate. The resolution order is:
 * <ol>
 * <li>the most specific {@link TransactionRunnerAware} bean covering the aggregate
 * class (selection via {@link AwareSelection}, so interfaces count and a bean naming
 * the aggregate itself wins over one naming an interface it implements),</li>
 * <li>a plain {@link TransactionRunner} bean of the application, which serves every
 * aggregate not covered by an aware bean,</li>
 * <li>the platform's own runner, if it can work at all - on Spring Boot that means a
 * unique <code>PlatformTransactionManager</code> exists,</li>
 * <li><code>null</code> if nothing usable is left. The core then ends the boot with a
 * guiding message including {@link #remediesDescription()}, because neither a workflow
 * start nor a task delivery could run.</li>
 * </ol>
 * The resolution is cached per aggregate class by the caller - it must not happen per
 * delivery.
 */
public interface TransactionRunnerResolver {

  /**
   * Resolves the runner for aggregates of the given class.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The runner or <code>null</code> if nothing usable is available
   * @throws IllegalStateException If two {@link TransactionRunnerAware} beans cover the
   *           aggregate at the same inheritance distance - the guiding message names
   *           both beans
   */
  TransactionRunner resolveFor(
      Class<?> workflowAggregateClass);

  /**
   * Platform-specific remedy lines appended to the core's guiding message when no
   * runner is available (e.g. which transaction manager to define).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

  /**
   * Which of the four resolution steps produced the runner of the given aggregate, in
   * words, for the one startup line naming it. Implementations answer for the same
   * aggregate class {@link #resolveFor(Class)} was called with.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return A short description like "TransactionRunnerAware bean 'ledgerTransactions'"
   */
  String describeResolutionFor(
      Class<?> workflowAggregateClass);

  /**
   * What the platform can tell about the transaction covering this aggregate's stores.
   * The default is {@link TransactionCoverage#unknown()}: a platform which cannot judge
   * stays silent rather than inventing a verdict.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The coverage verdict and its message
   */
  default TransactionCoverage coverageOf(
      final Class<?> workflowAggregateClass) {

    return TransactionCoverage.unknown();

  }

}
