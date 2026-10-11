package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;

/**
 * Resolves the {@link TransactionRunner} which is used for a workflow aggregate. The
 * platform integrations implement it. The core calls it at startup (see
 * {@link MigrationProcessService#validateTransactionRunnerAtStartup()}), and lazily for every
 * operation which touches that aggregate. The order is:
 * <ol>
 * <li>the most specific {@link TransactionRunnerAware} bean which covers the aggregate class
 * (picked by {@link AwareSelection}, so interfaces count, and a bean which names the
 * aggregate itself wins over one which names an interface it implements),</li>
 * <li>a plain {@link TransactionRunner} bean of the application, which serves every
 * aggregate that no aware bean covers,</li>
 * <li>the platform's own runner, if it can work at all. On Spring Boot that means a unique
 * <code>PlatformTransactionManager</code> exists,</li>
 * <li><code>null</code> if nothing usable is left. Then the core ends the boot with a
 * guiding message which includes {@link #remediesDescription()}, because neither a workflow
 * start nor a task delivery could run.</li>
 * </ol>
 * The caller caches the result per aggregate class. It must not be resolved per
 * delivery.
 * <p>
 * Decision 11 in the repository's DECISIONS.md says why the runner is resolved per workflow
 * aggregate, and why a tie ends the start.
 */
public interface TransactionRunnerResolver {

  /**
   * Resolves the runner for aggregates of the given class.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The runner or <code>null</code> if nothing usable is available
   * @throws IllegalStateException If two {@link TransactionRunnerAware} beans cover the
   *           aggregate at the same inheritance distance. The guiding message names both
   *           beans
   */
  TransactionRunner resolveFor(
      Class<?> workflowAggregateClass);

  /**
   * Lines with remedies for this platform. They are added to the core's guiding message when
   * no runner is available (for example which transaction manager to define).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

  /**
   * Which of the four steps produced the runner of the given aggregate, in words, for the one
   * startup line which names it. Implementations answer for the same aggregate class with
   * which {@link #resolveFor(Class)} was called.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return A short description, for example "TransactionRunnerAware bean
   *         'ledgerTransactions'"
   */
  String describeResolutionFor(
      Class<?> workflowAggregateClass);

  /**
   * What the platform can tell about the transaction which covers the stores of this
   * aggregate. The default is {@link TransactionCoverage#unknown()}. A platform which cannot
   * judge stays silent, and does not invent a verdict.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The coverage verdict and its message
   */
  default TransactionCoverage coverageOf(
      final Class<?> workflowAggregateClass) {

    return TransactionCoverage.unknown();

  }

}
