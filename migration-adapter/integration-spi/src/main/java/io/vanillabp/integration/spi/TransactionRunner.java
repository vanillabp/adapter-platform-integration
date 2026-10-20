package io.vanillabp.integration.spi;

import java.util.function.Supplier;

/**
 * A neutral way to run transactions. The core uses it to run
 * <code>&#64;WorkflowTask</code> handlers (load aggregate, invoke, save) in a
 * transaction. It also uses it to call back into the application when phase two of a
 * two-phase operation is dispatched.
 * <p>
 * Each platform provides an implementation. Spring uses <code>TransactionTemplate</code>.
 * Quarkus uses <code>QuarkusTransaction</code>, and also activates the CDI request
 * context around the work, because handlers are invoked on adapter threads. It covers
 * whatever the transaction manager of the platform covers.
 * <p>
 * <b>Some applications store their workflow aggregates in a system the platform does
 * not manage</b> (an event store, a ledger, a service behind an API, a message
 * producer). Such an application implements this interface itself. It contributes it as
 * a plain bean, which serves every aggregate of the application, or through a
 * {@link TransactionRunnerAware} bean, which serves the aggregates it names.
 * VanillaBP then opens, commits and rolls back the unit of work of the application
 * instead of the one of the platform. There is still exactly ONE transaction around
 * loading the aggregate, running the handler and saving it.
 * <p>
 * Contract: a {@link RuntimeException} thrown by the work rolls the transaction back,
 * and it is passed on to the caller. A normal return commits. Implementations must be
 * thread-safe. Handlers are invoked on the threads of the BPMS adapters, and phase two
 * runs on the thread of the outbox dispatcher.
 * <p>
 * Why VanillaBP opens a transaction of its own before it calls the application, and why the runner
 * is chosen per workflow aggregate and not taken from the platform, is decision 11 in the
 * repository's DECISIONS.md.
 */
public interface TransactionRunner {

  /**
   * Runs the given work in a NEW transaction. It suspends a transaction which may be
   * active ("requires new").
   *
   * @param <T> The result type
   * @param work The work to run
   * @return The work's result
   */
  <T> T requireNew(
      Supplier<T> work);

  /**
   * Runs the given work in the transaction which is already active on the calling
   * thread ("mandatory"). Embedded BPMS like Camunda 7 invoke handlers inside the
   * transaction of the engine. If no transaction is active, implementations fail with a
   * message which guides the developer.
   *
   * @param <T> The result type
   * @param work The work to run
   * @return The work's result
   */
  <T> T inCurrent(
      Supplier<T> work);

  /**
   * Runs the given work in a transaction. It JOINS the transaction active on the calling
   * thread, and starts a new one otherwise ("required").
   * <p>
   * The phase-two dispatch uses this. VanillaBP calls back into the application from the
   * own thread of the outbox dispatcher, because the workflow aggregate has to be loaded to
   * build what the BPMS is told. On that thread the platform provides nothing by itself.
   * An outbox implementation which dispatches inside a transaction of its own (e.g.
   * gruelbox on Spring Boot) keeps it. Everything else gets one from VanillaBP.
   * <p>
   * The default runs the work in a new transaction. Platforms which activate more
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
   * Whether a transaction of this runner is open on the calling thread right now.
   * <p>
   * VanillaBP asks this before an operation which persists the workflow aggregate for the
   * caller (a workflow start, an answer to a task through the API). Those operations write
   * the aggregate and, for a remote BPMS, the phase-two outbox entry. Both belong into the
   * unit of work of the caller. If the answer is <code>false</code>, the call is refused
   * with a message which tells the developer to open one.
   * <p>
   * The default is <code>true</code>, which means "I cannot tell". If the runner of an
   * application does not answer, the application stays responsible for opening its unit of
   * work. The platform implementations really answer (Spring: a transaction is active;
   * Quarkus: a JTA transaction is associated with the thread).
   *
   * @return Whether work handed to this runner would join something already open
   */
  default boolean isTransactionActive() {

    return true;

  }

  /**
   * Whether the transaction of the work which runs right now was marked rollback-only.
   * Then it can no longer commit. Only a transaction annotation of the application can do
   * that to the transaction of VanillaBP, and the mark cannot be cleared, neither in Spring
   * nor in JTA.
   * <p>
   * The answer is only valid while work passed to {@link #requireNew(Supplier)},
   * {@link #inCurrent(Supplier)} or {@link #requireTransaction(Supplier)} is running. Outside
   * of that, the answer is <code>false</code>.
   *
   * @return <code>true</code> if the current transaction can no longer commit
   */
  boolean isRollbackOnly();

  /**
   * Runs the given check as LATE as possible: right before the transaction which is open
   * on this thread commits.
   * <p>
   * In phase one, a remote BPMS is asked whether an operation still makes sense. Is the
   * task still there? Does the model know this message? The answer can go stale between
   * the question and phase two. Asking right before the commit keeps that window as small
   * as the platform allows. A check which throws stops the commit, and that is exactly
   * what a phase-one check is for.
   * <p>
   * Only the owner of the transaction can offer this, and that is why it lives here. An
   * application which brought its own unit of work knows how to hook into it. The default
   * runs the check at once, as every runner did before this method existed. That is
   * correct, just with a wider window. The platform implementations hook into their
   * transaction (Spring: a <code>TransactionSynchronization</code>; Quarkus: an interposed
   * JTA <code>Synchronization</code>). If no transaction is active, they run the check at
   * once.
   *
   * @param check The check to run; if it throws, the commit is stopped
   */
  default void beforeCommit(
      final Runnable check) {

    check.run();

  }

  /**
   * Whether the given failure says that another writer changed the workflow aggregate in
   * between. That is an optimistic locking conflict on an aggregate with a version
   * attribute, or a write conflict which MongoDB reports inside a transaction, with or
   * without a version attribute. Only the owner of the transaction can tell. Spring reports
   * the first as <code>OptimisticLockingFailureException</code>, and the second as a
   * <code>DataIntegrityViolationException</code> caused by a <code>MongoException</code>
   * with error code 112. JTA on Quarkus wraps
   * <code>jakarta.persistence.OptimisticLockException</code> in a
   * <code>RollbackException</code>. The own storage of an application knows its own
   * exception. Implementations unwrap the causes, because both platforms wrap.
   * <p>
   * The answer only decides whether VanillaBP logs its message about a version conflict,
   * nothing else. The exception is passed on unchanged either way. VanillaBP never retries
   * a conflict itself.
   *
   * @param failure The exception the transactional work or its commit produced
   * @return Whether it is a version conflict; <code>false</code> if the platform
   *         cannot tell
   */
  default boolean isConcurrentModification(
      final Throwable failure) {

    // a platform which does not classify turns the message off and does not make up
    // an answer. Test doubles of this interface are the typical case
    return false;

  }

}
