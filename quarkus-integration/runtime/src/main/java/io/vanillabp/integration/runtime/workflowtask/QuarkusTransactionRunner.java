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
 * request context is active, and Panache/Hibernate session access requires one.
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
  public boolean isRollbackOnly() {

    // getRollbackOnly() is only defined while a transaction is associated with the
    // thread, so the status is asked first
    return (transactionRegistry.getTransactionStatus() != Status.STATUS_NO_TRANSACTION) && transactionRegistry
        .getRollbackOnly();

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
