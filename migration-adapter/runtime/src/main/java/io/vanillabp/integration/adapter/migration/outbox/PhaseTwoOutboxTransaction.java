package io.vanillabp.integration.adapter.migration.outbox;

/**
 * The transaction the caller is in, as far as an outbox store needs to know it: whether
 * one is running at all, and how to be told that it committed.
 * <p>
 * This is the one half of the JDBC outbox which cannot be platform-neutral. Spring Boot
 * keeps its transaction in a thread-local of
 * {@code TransactionSynchronizationManager}, Quarkus asks the JTA
 * {@code TransactionSynchronizationRegistry}, and a platform which arrives later brings
 * whatever it has. Everything else the store and its dispatcher do is the same on both,
 * which is why they live in the core and this interface travels with them.
 * <p>
 * The connection itself is not part of this: a store gets it from a
 * {@link io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess},
 * which both platforms already implement for the payload store and the delivery log.
 */
public interface PhaseTwoOutboxTransaction {

  /**
   * Whether a transaction is running on this thread. An outbox entry has to be written
   * in the transaction which persists the workflow aggregate, so a store called without
   * one refuses instead of writing an entry nobody can roll back.
   *
   * @return Whether the caller is in a transaction
   */
  boolean isActive();

  /**
   * Runs the given action once the running transaction committed, and not at all where
   * it rolled back. The outbox dispatches an entry right after its commit this way,
   * instead of waiting for the next poll.
   *
   * @param action What to run after the commit
   */
  void afterCommit(
      Runnable action);

}
