package io.vanillabp.integration.workflowtask;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;

/**
 * The Spring implementation of the core's {@link TransactionRunner} used to run
 * <code>&#64;WorkflowTask</code> handlers: {@link TransactionTemplate} with
 * propagation REQUIRES_NEW respectively MANDATORY. The
 * {@link PlatformTransactionManager} is resolved lazily - an application without
 * transactional persistence can still boot and gets a guiding message when the
 * first task is processed.
 */
public class SpringTransactionRunner implements TransactionRunner {

  private final ObjectProvider<PlatformTransactionManager> transactionManager;

  private final ThreadLocal<TransactionStatus> currentStatus = new ThreadLocal<>();

  public SpringTransactionRunner(
      final ObjectProvider<PlatformTransactionManager> transactionManager) {

    this.transactionManager = transactionManager;

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

  @Override
  public boolean isRollbackOnly() {

    final var status = currentStatus.get();
    // Spring reports the mark for a PARTICIPATING transaction as well, which is the
    // silent case: the commit of the participating transaction returns normally
    // while nothing can be committed
    return (status != null) && status.isRollbackOnly();

  }

  @Override
  public boolean isConcurrentModification(
      final Throwable failure) {

    // OptimisticLockingFailureException is Spring's translation for every
    // persistence technology it integrates - JPA (ObjectOptimisticLockingFailure-
    // Exception) as well as MongoDB. The causes are walked because the conflict
    // arrives wrapped as often as not: the commit of the transaction template wraps
    // what the persistence provider threw while flushing.
    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof org.springframework.dao.OptimisticLockingFailureException) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    // a provider exception which never passed Spring's translation (e.g. thrown by
    // an application's own repository code) means the same thing
    return io.vanillabp.integration.adapter.migration.transaction.AggregateWrite
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
              @WorkflowTask methods load and save the workflow aggregate within a transaction. \
              Add a transactional persistence (e.g. spring-boot-starter-data-jpa with a data \
              source) or define a PlatformTransactionManager bean.""");
    }
    final var transactionTemplate = new TransactionTemplate(manager);
    transactionTemplate.setPropagationBehavior(propagation);
    return transactionTemplate.execute(status -> {
      // the status is the only way to read the rollback-only mark, and it is
      // available inside the callback only - handlers run on adapter threads, so a
      // thread local is enough to reach it from the core's check
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
