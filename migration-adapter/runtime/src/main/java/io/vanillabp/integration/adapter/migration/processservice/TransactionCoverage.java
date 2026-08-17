package io.vanillabp.integration.adapter.migration.processservice;

/**
 * What a platform can tell about the transaction covering the stores of one workflow
 * aggregate, reported by {@link TransactionRunnerResolver#coverageOf(Class)} and turned
 * into a startup message by
 * {@link MigrationProcessService#validateTransactionRunnerAtStartup()}.
 * <p>
 * The verdict decides whether the application boots, the message is what the developer
 * reads. Only the platform can judge this: it knows its transaction managers, and it
 * knows which of its own defaults manage the aggregate.
 *
 * @param verdict What the platform found
 * @param message The guiding message, <code>null</code> for
 *          {@link Verdict#COVERED} and {@link Verdict#UNKNOWN}
 */
public record TransactionCoverage(Verdict verdict, String message) {

  public enum Verdict {

    /**
     * The transaction VanillaBP opens covers the aggregate's store. Nothing is
     * logged.
     */
    COVERED,

    /**
     * The store is writable but demonstrably not covered by the transaction - the
     * writes of one workflow step no longer commit or roll back together. A WARN
     * naming what that costs and what to check; the application boots.
     */
    UNGUARDED,

    /**
     * The combination cannot work as promised and the platform can name the fix, e.g.
     * a MongoDB-managed aggregate in a Spring Boot application whose only transaction
     * manager is a JPA one. The boot ends, unless the application states that it
     * accepts unguarded writes
     * (<code>vanillabp.transactions.unguarded-aggregate-writes</code>), in which case
     * the same message is logged as a WARN.
     */
    UNCOVERABLE,

    /**
     * The platform cannot judge the store, which is the normal answer for an
     * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} implementation of
     * the application writing wherever it wants. Nothing is logged - inventing a
     * verdict would be worse than staying silent.
     */
    UNKNOWN

  }

  /**
   * @return A covered verdict
   */
  public static TransactionCoverage covered() {

    return new TransactionCoverage(Verdict.COVERED, null);

  }

  /**
   * @return A verdict the platform cannot give
   */
  public static TransactionCoverage unknown() {

    return new TransactionCoverage(Verdict.UNKNOWN, null);

  }

  /**
   * @param message The guiding message naming what is given up and what to check
   * @return An unguarded verdict
   */
  public static TransactionCoverage unguarded(
      final String message) {

    return new TransactionCoverage(Verdict.UNGUARDED, message);

  }

  /**
   * @param message The guiding message naming the fix
   * @return An uncoverable verdict
   */
  public static TransactionCoverage uncoverable(
      final String message) {

    return new TransactionCoverage(Verdict.UNCOVERABLE, message);

  }

}
