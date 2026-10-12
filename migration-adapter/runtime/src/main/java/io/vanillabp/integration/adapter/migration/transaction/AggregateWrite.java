package io.vanillabp.integration.adapter.migration.transaction;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Runs work which the core wraps in a transaction, and says what a version conflict on
 * saving the workflow aggregate means. Such work is processing a task, building the
 * aggregate of a workflow the BPMS started, reporting the end of a workflow, or answering
 * an extension.
 * <p>
 * The caller says which transaction it means, with a {@link TransactionForm}. A task
 * arrives from an adapter, and the adapter knows whether it calls inside a transaction of
 * its own. So there the form follows the adapter. A handler of an extension is called from
 * wherever the extension was called, and nobody can know that in advance. So there the form
 * joins the running transaction and opens one only where none runs.
 * <p>
 * As soon as a BPMN process has more than one token, two branches write the same workflow
 * aggregate. One writes in the transaction which VanillaBP owns. The other writes in a
 * transaction which the application opens around an API call. If the aggregate has a
 * version attribute, this collision becomes an exception instead of a silent overwrite. The
 * exception shows up at the COMMIT which VanillaBP performs, where the application cannot
 * catch it.
 * <p>
 * <b>VanillaBP does not retry.</b> A handler may have called a remote API before the commit
 * failed. A quiet retry inside the framework would repeat that call and hide that anything
 * went wrong. So one guiding message names the conflict, and the exception is passed on
 * UNCHANGED. The adapter maps it to the way its BPMS retries: again and again until the
 * attempts are used up, then an incident. Sometimes the work ran in a transaction which
 * VanillaBP did not open. That is {@link TransactionForm#CURRENT} for an embedded Camunda
 * 7, or {@link TransactionForm#CURRENT_OR_NEW} inside a transaction of the application.
 * Then whoever opened the transaction owns the commit, and VanillaBP never sees the conflict.
 * <p>
 * Decision 14 in the repository's DECISIONS.md says why a version conflict is reported and
 * passed on unchanged instead of being retried.
 */
public final class AggregateWrite {

  private static final Logger log = LoggerFactory.getLogger(AggregateWrite.class);

  private AggregateWrite() {
  }

  /**
   * Runs the given work in a transaction. If a version conflict comes out of it, this
   * reports the conflict.
   *
   * @param <T> The result type
   * @param transactionRunner The platform's transaction runner. It also classifies the
   *          failure, because only the platform knows its exceptions
   * @param transactionForm Which of the three ways of working in a transaction is meant
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The workflow aggregate's ID
   * @param operation What was going on, in a form fitting "... failed": e.g.
   *          "processing task 'approve'"
   * @param work The work to run
   * @return The work's result
   */
  public static <T> T inTransaction(
      final TransactionRunner transactionRunner,
      final TransactionForm transactionForm,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String operation,
      final Supplier<T> work) {

    try {
      return transactionForm.runIn(transactionRunner, work);
    } catch (final RuntimeException failure) {
      if (transactionRunner.isConcurrentModification(failure)) {
        log
            .error(
                """
                    {} of workflow aggregate '{}' (BPMN process '{}' of workflow module '{}') failed \
                    on a version conflict: another writer changed that aggregate meanwhile, so \
                    nothing this run changed was saved. VanillaBP does not retry - the BPMS decides \
                    what happens next (its retries, and an incident once they are used up), and a \
                    repeated run repeats everything the method did outside the transaction as well. \
                    Two writers appear as soon as a workflow has more than one token (e.g. a \
                    non-interrupting boundary event) or the application changes the aggregate \
                    through its own API while the workflow runs, and an extension writing while it \
                    reports is a third way to get there. The wiki page 'Workflow aggregates' names \
                    every pair of writers and the way out of each of them, in the section 'Two \
                    writers on one aggregate'.""",
                capitalized(operation),
                workflowAggregateId == null
                    ? "not assigned yet"
                    : workflowAggregateId,
                bpmnProcessId,
                workflowModuleId);
      }
      throw failure;
    }

  }

  /**
   * Whether a persistence layer raised one of its exceptions for a version conflict. It
   * matches them by NAME along the chain of causes. Both platform implementations of
   * {@link TransactionRunner#isConcurrentModification(Throwable)} use it for the part which
   * does not depend on the platform. JPA reports
   * <code>jakarta.persistence.OptimisticLockException</code> both under Spring and under
   * JTA, and Hibernate's own <code>org.hibernate.StaleObjectStateException</code> comes
   * along as its cause.
   * <p>
   * It matches names and not types, because the core is plain Java. It must not depend on
   * JPA, Hibernate or MongoDB just to recognize their exceptions.
   *
   * @param failure The exception the transactional work or its commit produced
   * @return Whether a version conflict is somewhere in the chain of causes
   */
  public static boolean causedByOptimisticLocking(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      final var name = candidate
          .getClass()
          .getName();
      if (OPTIMISTIC_LOCKING_EXCEPTIONS.contains(name)) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return false;

  }

  private static final java.util.Set<String> OPTIMISTIC_LOCKING_EXCEPTIONS = java.util.Set
      .of(
          "jakarta.persistence.OptimisticLockException",
          "javax.persistence.OptimisticLockException",
          "org.hibernate.StaleObjectStateException",
          "org.hibernate.StaleStateException",
          "io.quarkus.mongodb.panache.common.exception.OptimisticLockException");

  private static String capitalized(
      final String operation) {

    if ((operation == null) || operation.isEmpty()) {
      return "Saving";
    }
    return Character.toUpperCase(operation.charAt(0)) + operation.substring(1);

  }

}
