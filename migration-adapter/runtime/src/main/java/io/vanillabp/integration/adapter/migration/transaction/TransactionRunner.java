package io.vanillabp.integration.adapter.migration.transaction;

import java.util.function.Supplier;

/**
 * Neutral transaction abstraction used by the core to run
 * <code>&#64;WorkflowTask</code> handlers (load aggregate - invoke - save) within a
 * transaction. Implemented per platform (Spring:
 * <code>TransactionTemplate</code>-based; Quarkus:
 * <code>QuarkusTransaction</code>-based, additionally activating the CDI request
 * context around the work because handlers are invoked on adapter threads).
 * <p>
 * Contract: a {@link RuntimeException} thrown by the work rolls the transaction
 * back and propagates to the caller; a normal return commits.
 */
public interface TransactionRunner {

  /**
   * Runs the given work in a NEW transaction, suspending a possibly active one
   * (semantics of "requires new").
   *
   * @param <T> The result type
   * @param work The work to run
   * @return The work's result
   */
  <T> T requireNew(
      Supplier<T> work);

  /**
   * Runs the given work within the transaction already active on the calling
   * thread (semantics of "mandatory": embedded BPMS like Camunda 7 invoke handlers
   * inside the engine's transaction). Implementations fail with a guiding message
   * if no transaction is active.
   *
   * @param <T> The result type
   * @param work The work to run
   * @return The work's result
   */
  <T> T inCurrent(
      Supplier<T> work);

  /**
   * Whether the transaction of the work currently running was marked rollback-only,
   * which no longer allows a commit. Only a transaction annotation of the
   * application can do that to VanillaBP's transaction, and the mark cannot be
   * cleared, in neither Spring nor JTA.
   * <p>
   * Valid only while work handed to {@link #requireNew(Supplier)} or
   * {@link #inCurrent(Supplier)} is running; outside of that the answer is
   * <code>false</code>.
   *
   * @return <code>true</code> if the current transaction can no longer commit
   */
  boolean isRollbackOnly();

  /**
   * Whether the given failure says that the workflow aggregate was changed by
   * another writer in between (an optimistic locking conflict on an aggregate
   * carrying a version attribute). Only the platform can tell: Spring reports it as
   * <code>OptimisticLockingFailureException</code> (JPA as well as MongoDB), JTA on
   * Quarkus wraps <code>jakarta.persistence.OptimisticLockException</code> in a
   * <code>RollbackException</code>. Implementations unwrap the causes, because both
   * platforms wrap.
   * <p>
   * The answer decides whether the guiding message of
   * {@link AggregateWrite} is logged - nothing else: the exception is propagated
   * unchanged either way, VanillaBP never retries a conflict itself.
   *
   * @param failure The exception the transactional work or its commit produced
   * @return Whether it is a version conflict; <code>false</code> if the platform
   *         cannot tell
   */
  default boolean isConcurrentModification(
      final Throwable failure) {

    // a platform which does not classify silences the message rather than inventing
    // an answer - test doubles of this interface are the typical case
    return false;

  }

}
