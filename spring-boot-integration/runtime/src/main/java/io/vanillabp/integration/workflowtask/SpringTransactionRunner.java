package io.vanillabp.integration.workflowtask;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The Spring implementation of the core's {@link TransactionRunner}, which runs
 * <code>&#64;WorkflowTask</code> handlers. For each form the core asks for, it uses a
 * {@link TransactionTemplate}: with propagation REQUIRES_NEW for {@code requireNew},
 * MANDATORY for {@code inCurrent} and REQUIRED for {@code requireTransaction}. The
 * {@link PlatformTransactionManager} is looked up lazily. So an application without
 * transactional persistence can still boot. It gets a guiding message when the first
 * task is processed.
 */
public class SpringTransactionRunner implements TransactionRunner {

  private final ObjectProvider<PlatformTransactionManager> transactionManager;

  private final ThreadLocal<TransactionStatus> currentStatus = new ThreadLocal<>();

  /**
   * The platform's own runner, built once by the autoconfiguration. The manager arrives as
   * a provider because it is asked for at the first task, not while the application
   * boots. An application without transactional persistence still starts, and the
   * startup check tells it what to add.
   *
   * @param transactionManager Asked for the unique <code>PlatformTransactionManager</code>
   *     of the application, which may be none
   */
  public SpringTransactionRunner(
      final ObjectProvider<PlatformTransactionManager> transactionManager) {

    this.transactionManager = transactionManager;

  }

  /**
   * A runner bound to ONE transaction manager. An application with mixed persistence needs
   * this shape. With a JPA and a MongoDB manager in the same application, no manager is
   * unique. So the application assigns its aggregates to runners built from the matching
   * manager, with {@link io.vanillabp.integration.spi.TransactionRunnerAware} beans.
   *
   * @param transactionManager The manager covering the aggregates this runner serves
   */
  public SpringTransactionRunner(
      final PlatformTransactionManager transactionManager) {

    this.transactionManager = new ObjectProvider<>() {

      @Override
      public PlatformTransactionManager getObject() {
        return transactionManager;
      }

      @Override
      public PlatformTransactionManager getObject(
          final Object... args) {
        return transactionManager;
      }

      @Override
      public PlatformTransactionManager getIfAvailable() {
        return transactionManager;
      }

      @Override
      public PlatformTransactionManager getIfUnique() {
        return transactionManager;
      }

    };

  }

  /**
   * Registers a {@link org.springframework.transaction.support.TransactionSynchronization}
   * whose <code>beforeCommit</code> runs the check. So a phase-one check of a BPMS adapter
   * runs as late as this platform allows. A check which throws aborts the commit. If
   * transaction synchronization is not active, the check runs at once.
   */
  @Override
  public void beforeCommit(
      final Runnable check) {

    if (!TransactionSynchronizationManager
        .isSynchronizationActive()) {
      check.run();
      return;
    }
    TransactionSynchronizationManager
        .registerSynchronization(
            new TransactionSynchronization() {

              @Override
              public void beforeCommit(
                  final boolean readOnly) {

                check.run();

              }

            });

  }


  /**
   * Whether this runner can work at all. On Spring Boot, that means a unique
   * {@link PlatformTransactionManager} exists.
   * {@link io.vanillabp.integration.processservice.SpringTransactionRunnerResolver} asks
   * it. A runner which cannot open a transaction must not be handed out as the platform's
   * default. Instead, the startup check reports the situation with every remedy.
   *
   * @return Whether a unique transaction manager is available
   */
  public boolean isUsable() {

    return transactionManager.getIfUnique() != null;

  }

  /**
   * The transaction manager this runner uses. The startup check uses it to name what the
   * runner covers.
   *
   * @return The manager or <code>null</code> if there is none (or several)
   */
  public PlatformTransactionManager getTransactionManager() {

    return transactionManager.getIfUnique();

  }

  @Override
  public <T> T requireNew(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_REQUIRES_NEW);

  }

  @Override
  public <T> T inCurrent(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_MANDATORY);

  }

  /**
   * Joins the transaction open on this thread, and opens one if there is none.
   * <p>
   * Where the outbox of this platform is based on gruelbox, it dispatches inside a
   * transaction of its own. The entry is ticked off in that transaction. A second
   * transaction around the call would commit what the application wrote while the entry is
   * still open. If the dispatch failed after that, it would repeat a call whose changes
   * are already in the database.
   */
  @Override
  public <T> T requireTransaction(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_REQUIRED);

  }

  @Override
  public boolean isTransactionActive() {

    return TransactionSynchronizationManager
        .isActualTransactionActive();

  }

  @Override
  public boolean isRollbackOnly() {

    final var status = currentStatus.get();
    // Spring also reports the mark for a PARTICIPATING transaction. That is the silent
    // case: the commit of the participating transaction returns normally, but nothing
    // can be committed
    return (status != null) && status.isRollbackOnly();

  }

  @Override
  public boolean isConcurrentModification(
      final Throwable failure) {

    // OptimisticLockingFailureException is Spring's translation for every persistence
    // technology it integrates: JPA (ObjectOptimisticLockingFailureException) as well as
    // MongoDB. The causes are walked because the conflict often arrives wrapped. The
    // commit of the transaction template wraps what the persistence provider threw
    // while flushing.
    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof OptimisticLockingFailureException) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    // a provider exception which never passed Spring's translation means the same thing,
    // e.g. one thrown by an application's own repository code
    return AggregateWrite
        .causedByOptimisticLocking(failure);

  }

  private <T> T run(
      final Supplier<T> work,
      final int propagation) {

    final var manager = transactionManager.getIfUnique();
    if (manager == null) {
      throw new IllegalStateException(
          """
              No (unique) PlatformTransactionManager is available to process a BPMN task! \
              @WorkflowTask methods load and save the workflow aggregate within one transaction, \
              and a relational database is only one way to get one. To solve this either
              - define a transaction manager covering the persistence of your workflow \
              aggregates: a JPA or JDBC one (e.g. spring-boot-starter-data-jpa with a data \
              source), or a MongoTransactionManager if they live in MongoDB (which needs the \
              deployment to be a replica set),
              - define a bean implementing io.vanillabp.integration.spi.TransactionRunner, which \
              serves every workflow aggregate of this application, or
              - define a bean implementing io.vanillabp.integration.spi.TransactionRunnerAware to \
              provide a runner for a single aggregate (or for an interface all your aggregates \
              implement).
              If several transaction managers exist, none of them is unique - name the one \
              VanillaBP has to use by contributing a TransactionRunner bean built from it.""");
    }
    final var transactionTemplate = new TransactionTemplate(manager);
    transactionTemplate.setPropagationBehavior(propagation);
    return transactionTemplate.execute(status -> {
      // the status is the only way to read the rollback-only mark, and it is only
      // available inside the callback. Handlers run on adapter threads, so a thread
      // local is enough to reach it from the core's check
      final var previous = currentStatus.get();
      currentStatus.set(status);
      try {
        return work.get();
      } finally {
        if (previous == null) {
          currentStatus.remove();
        } else {
          currentStatus.set(previous);
        }
      }
    });

  }

}
