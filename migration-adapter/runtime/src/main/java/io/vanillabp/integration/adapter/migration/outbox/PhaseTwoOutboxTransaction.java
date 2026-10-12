package io.vanillabp.integration.adapter.migration.outbox;

/**
 * The caller's transaction, as far as an outbox store needs to know it: whether one is
 * running at all, and how to learn that it committed.
 * <p>
 * This is the one half of the JDBC outbox which cannot be the same on all platforms. Spring
 * Boot keeps its transaction in a thread-local of
 * {@code TransactionSynchronizationManager}. Quarkus asks the JTA
 * {@code TransactionSynchronizationRegistry}. A platform which comes later brings whatever
 * it has. Everything else which the store and its dispatcher do is the same on both. That is
 * why they live in the core, and this interface travels with them.
 * <p>
 * The connection itself is not part of this. A store gets it from a
 * {@link io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess}, which
 * both platforms already implement for the payload store and the delivery log.
 */
public interface PhaseTwoOutboxTransaction {

  /**
   * Whether a transaction is running on this thread. An outbox entry has to be written in
   * the transaction which persists the workflow aggregate. So a store which is called without
   * one refuses, instead of writing an entry which nobody can roll back.
   *
   * @return Whether the caller is in a transaction
   */
  boolean isActive();

  /**
   * Runs the given action once the running transaction has committed, and not at all where
   * it rolled back. This way the outbox dispatches an entry right after its commit, instead
   * of waiting for the next poll.
   *
   * @param action What to run after the commit
   */
  void afterCommit(
      Runnable action);

}
