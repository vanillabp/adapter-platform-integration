package io.vanillabp.integration.runtime.workflowtask;

import java.util.function.Supplier;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * The Quarkus implementation of the core's {@link TransactionRunner} used to run
 * <code>&#64;WorkflowTask</code> handlers: JTA via {@link QuarkusTransaction}.
 * Additionally the CDI request context is activated around the work - handlers are
 * invoked on adapter threads (e.g. job-executor or polling-worker threads) where no
 * request context is active, and Panache/Hibernate session access requires one. The
 * same runner provides both to phase-two dispatch, which calls the application's
 * persistence from the outbox dispatcher's thread (see
 * {@link #requireTransaction(Supplier)}).
 */
public class QuarkusTransactionRunner implements TransactionRunner {

  private final TransactionSynchronizationRegistry transactionRegistry;

  public QuarkusTransactionRunner(
      final TransactionSynchronizationRegistry transactionRegistry) {

    this.transactionRegistry = transactionRegistry;

  }

  @Override
  public <T> T requireNew(
      final Supplier<T> work) {

    return withRequestContext(() -> QuarkusTransaction
        .requiringNew()
        .call(work::get));

  }

  @Override
  public <T> T inCurrent(
      final Supplier<T> work) {

    // STATUS_NO_TRANSACTION is the only status reported for a thread without a
    // transaction; every other status means one is associated with this thread, no
    // matter whether it was already marked for rollback
    if (transactionRegistry.getTransactionStatus() == Status.STATUS_NO_TRANSACTION) {
      throw new IllegalStateException(
          """
              A @WorkflowTask handler was to be run within the caller's transaction but no \
              transaction is active on the current thread! The BPMS adapter requested joining the \
              current transaction (TaskInvocationContext.runInCurrentTransaction) - this is only \
              valid for embedded BPMS invoking handlers inside the engine's transaction.""");
    }
    return withRequestContext(work);

  }

  @Override
  public <T> T requireTransaction(
      final Supplier<T> work) {

    // joining instead of suspending: an outbox of the application may well dispatch
    // inside a transaction of its own, and phase two belongs into it then
    return withRequestContext(() -> QuarkusTransaction
        .joiningExisting()
        .call(work::get));

  }

  @Override
  public boolean isRollbackOnly() {

    // getRollbackOnly() is only defined while a transaction is associated with the
    // thread, so the status is asked first
    return (transactionRegistry.getTransactionStatus() != Status.STATUS_NO_TRANSACTION) && transactionRegistry
        .getRollbackOnly();

  }

  @Override
  public boolean isConcurrentModification(
      final Throwable failure) {

    // under JTA the conflict never arrives as itself: Hibernate raises it while
    // flushing at commit, the transaction manager reports a RollbackException and
    // QuarkusTransaction wraps that again - so the chain of causes is what is asked,
    // which the core does by name (it must not depend on JPA)
    return io.vanillabp.integration.adapter.migration.transaction.AggregateWrite
        .causedByOptimisticLocking(failure);

  }

  private <T> T withRequestContext(
      final Supplier<T> work) {

    final var requestContext = Arc
        .container()
        .requestContext();
    if (requestContext.isActive()) {
      return work.get();
    }
    requestContext.activate();
    try {
      return work.get();
    } finally {
      requestContext.terminate();
    }

  }

}
