package io.vanillabp.integration.runtime.workflowtask;

import java.util.function.Supplier;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vanillabp.integration.spi.TransactionRunner;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * The Quarkus implementation of the core's {@link TransactionRunner}. It runs
 * <code>&#64;WorkflowTask</code> handlers in JTA transactions through
 * {@link QuarkusTransaction}. It also activates the CDI request context around the work.
 * Handlers run on adapter threads, for example job-executor or polling-worker threads. No
 * request context is active there, but Panache and Hibernate need one to use a session. The
 * same runner gives both to the phase-two dispatch. That dispatch calls the application's
 * persistence from the thread of the outbox dispatcher (see
 * {@link #requireTransaction(Supplier)}).
 */
public class QuarkusTransactionRunner implements TransactionRunner {

  private final TransactionSynchronizationRegistry transactionRegistry;

  /**
   * {@link io.vanillabp.integration.runtime.processservice.TransactionRunnerProducer} creates
   * it once per application and hands it out as the platform's runner bean. That there is only
   * one instance matters. The resolver tells the platform's runner from an application's runner
   * by identity.
   *
   * @param transactionRegistry Where the state of the transaction of the CURRENT THREAD is
   *          read from. JTA answers per thread, so this object has no state of its own
   */
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

  /**
   * Registers an interposed JTA {@link Synchronization}. Its
   * {@link Synchronization#beforeCompletion()} runs the check. So a phase-one check of an
   * adapter runs as late as the platform allows. Without an active transaction, the check runs
   * at once.
   */
  @Override
  public void beforeCommit(
      final Runnable check) {

    if (transactionRegistry.getTransactionStatus() != Status.STATUS_ACTIVE) {
      check.run();
      return;
    }
    transactionRegistry.registerInterposedSynchronization(new Synchronization() {

      @Override
      public void beforeCompletion() {

        check.run();

      }

      @Override
      public void afterCompletion(
          final int status) {
      }

    });

  }

  @Override
  public <T> T inCurrent(
      final Supplier<T> work) {

    // STATUS_NO_TRANSACTION is the only status for a thread without a transaction.
    // Every other status means that the thread has one, even if it is already marked
    // for rollback.
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

    // Join instead of suspend. An outbox of the application may dispatch inside a
    // transaction of its own, and then phase two belongs in that transaction.
    return withRequestContext(() -> QuarkusTransaction
        .joiningExisting()
        .call(work::get));

  }

  @Override
  public boolean isTransactionActive() {

    return transactionRegistry.getTransactionStatus() != Status.STATUS_NO_TRANSACTION;

  }

  @Override
  public boolean isRollbackOnly() {

    // getRollbackOnly() works only while the thread has a transaction. So we ask for
    // the status first.
    return (transactionRegistry.getTransactionStatus() != Status.STATUS_NO_TRANSACTION) && transactionRegistry
        .getRollbackOnly();

  }

  @Override
  public boolean isConcurrentModification(
      final Throwable failure) {

    // Under JTA, the conflict never arrives as itself. Hibernate raises it while it
    // flushes at commit. The transaction manager reports a RollbackException, and
    // QuarkusTransaction wraps that again. So we look at the chain of causes. The core
    // does this by class name, because it must not depend on JPA.
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
