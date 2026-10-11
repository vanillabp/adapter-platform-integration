package io.vanillabp.integration.adapter.migration.transaction;

import java.util.function.Supplier;

import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Which of the three ways of working in a transaction a caller of
 * {@link AggregateWrite#inTransaction} means. Each way is one method of
 * {@link TransactionRunner}. This class passes the choice on and does not judge it.
 * <p>
 * There used to be a boolean here, and it could name only two of the three. There was no
 * way to ask for "join one if it runs". So every caller had to know in advance whether a
 * transaction was running. Decision 44 in the repository's DECISIONS.md says which
 * transaction a call into the application gets.
 */
public enum TransactionForm {

  /**
   * A transaction of its own, which suspends any running one. What the work writes commits
   * on its own, so it stays even when a surrounding transaction rolls back.
   */
  NEW {

    @Override
    <T> T runIn(
        final TransactionRunner transactionRunner,
        final Supplier<T> work) {

      return transactionRunner.requireNew(work);

    }

  },

  /**
   * The transaction which is already running, and nothing else. An embedded BPMS calls
   * inside its own transaction, and the engine owns the commit there. If the thread has no
   * transaction, that is a mistake, and the runner reports it.
   */
  CURRENT {

    @Override
    <T> T runIn(
        final TransactionRunner transactionRunner,
        final Supplier<T> work) {

      return transactionRunner.inCurrent(work);

    }

  },

  /**
   * The transaction which is already running, or a new one which VanillaBP opens where none
   * runs. This form needs no knowledge about the caller. That is why a handler of an
   * extension gets it.
   */
  CURRENT_OR_NEW {

    @Override
    <T> T runIn(
        final TransactionRunner transactionRunner,
        final Supplier<T> work) {

      return transactionRunner.requireTransaction(work);

    }

  };

  /**
   * Runs the given work the way this form says.
   *
   * @param <T> The result type
   * @param transactionRunner The runner serving the workflow aggregate
   * @param work The work to run
   * @return The work's result
   */
  abstract <T> T runIn(
      TransactionRunner transactionRunner,
      Supplier<T> work);

  /**
   * The form which an adapter asks for, by saying whether it calls inside a transaction of
   * its own. An embedded BPMS answers yes and owns the commit. Every other adapter calls
   * from a worker thread without a transaction, and VanillaBP opens one.
   *
   * @param runInCurrentTransaction What the adapter's invocation context answers
   * @return The form to use
   */
  public static TransactionForm askedForBy(
      final boolean runInCurrentTransaction) {

    return runInCurrentTransaction
        ? CURRENT
        : NEW;

  }

}
