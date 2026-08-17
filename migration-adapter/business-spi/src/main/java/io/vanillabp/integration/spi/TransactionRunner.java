package io.vanillabp.integration.spi;

import java.util.function.Supplier;

/**
 * Neutral transaction abstraction used by the core to run
 * <code>&#64;WorkflowTask</code> handlers (load aggregate - invoke - save) within a
 * transaction, and to call back into the application when phase two of a two-phase
 * operation is dispatched.
 * <p>
 * Each platform provides an implementation (Spring:
 * <code>TransactionTemplate</code>-based; Quarkus:
 * <code>QuarkusTransaction</code>-based, additionally activating the CDI request
 * context around the work because handlers are invoked on adapter threads), and it
 * covers whatever the platform's transaction manager covers.
 * <p>
 * <b>An application storing its workflow aggregates in a system the platform does
 * not manage</b> (an event store, a ledger, a service behind an API, a message
 * producer) implements this interface itself and contributes it either as a plain
 * bean, which serves every aggregate of the application, or through a
 * {@link TransactionRunnerAware} bean, which serves the aggregates it names.
 * VanillaBP then opens, commits and rolls back the application's unit of work
 * instead of the platform's, and there is still exactly ONE transaction around
 * loading the aggregate, running the handler and saving it.
 * <p>
 * Contract: a {@link RuntimeException} thrown by the work rolls the transaction
 * back and propagates to the caller; a normal return commits. Implementations must
 * be thread-safe: handlers are invoked on the threads of the BPMS adapters and phase
 * two runs on the outbox dispatcher's thread.
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
   * Runs the given work in a transaction, JOINING the one active on the calling
   * thread and starting a new one otherwise (semantics of "required").
   * <p>
   * This is what phase-two dispatch uses: VanillaBP calls back into the application
   * (the workflow aggregate has to be loaded to build what the BPMS is told) from
   * the outbox dispatcher's own thread, where the platform provides nothing on its
   * own. An outbox implementation which dispatches inside a transaction of its own
   * (e.g. gruelbox on Spring Boot) keeps it, everything else gets one from VanillaBP.
   * <p>
   * The default runs the work in a new transaction - platforms activating additional
   * contexts (Quarkus: the CDI request context) override it.
   *
   * @param <T> The result type
   * @param work The work to run
   * @return The work's result
   */
  default <T> T requireTransaction(
      final Supplier<T> work) {

    return requireNew(work);

  }

  /**
   * Whether a transaction of this runner is currently open on the calling thread.
   * <p>
   * VanillaBP asks before an operation which persists the workflow aggregate on the
   * caller's behalf (a workflow start, an answer to a task through the API): those write
   * the aggregate and, for a remote BPMS, the phase-two outbox entry, and both belong into
   * the caller's unit of work. Where the answer is <code>false</code> the call is refused
   * with a message telling the developer to open one.
   * <p>
   * The default is <code>true</code>, which means "I cannot tell": an application's runner
   * which does not answer keeps the responsibility for opening its unit of work. The
   * platform implementations answer for real (Spring: an actual transaction is active;
   * Quarkus: a JTA transaction is associated with the thread).
   *
   * @return Whether work handed to this runner would join something already open
   */
  default boolean isTransactionActive() {

    return true;

  }

  /**
   * Whether the transaction of the work currently running was marked rollback-only,
   * which no longer allows a commit. Only a transaction annotation of the
   * application can do that to VanillaBP's transaction, and the mark cannot be
   * cleared, in neither Spring nor JTA.
   * <p>
   * Valid only while work handed to {@link #requireNew(Supplier)},
   * {@link #inCurrent(Supplier)} or {@link #requireTransaction(Supplier)} is running; outside of that the answer is
   * <code>false</code>.
   *
   * @return <code>true</code> if the current transaction can no longer commit
   */
  boolean isRollbackOnly();

  /**
   * Whether the given failure says that the workflow aggregate was changed by
   * another writer in between (an optimistic locking conflict on an aggregate
   * carrying a version attribute). Only whoever owns the transaction can tell: Spring
   * reports it as <code>OptimisticLockingFailureException</code> (JPA as well as
   * MongoDB), JTA on Quarkus wraps
   * <code>jakarta.persistence.OptimisticLockException</code> in a
   * <code>RollbackException</code>, and an application's own storage knows its own
   * exception. Implementations unwrap the causes, because both platforms wrap.
   * <p>
   * The answer decides whether VanillaBP logs its guiding message about a version
   * conflict (story 59) - nothing else: the exception is propagated unchanged either
   * way, VanillaBP never retries a conflict itself.
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
