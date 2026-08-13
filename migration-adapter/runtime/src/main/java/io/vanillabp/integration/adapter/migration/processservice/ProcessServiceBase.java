package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.spi.process.ProcessService;

/**
 * Platform-neutral base of the platform integrations' {@link ProcessService} beans.
 * All operations not yet implemented by VanillaBP 2 throw an
 * {@link UnsupportedOperationException} saying so - a silent no-op would hide the
 * missing implementation from the application developer, and an
 * {@link AbstractMethodError} would not explain it. The stubs are replaced by real
 * implementations story by story.
 *
 * @param <A> The workflow-aggregate-class
 */
public abstract class ProcessServiceBase<A> implements ProcessService<A> {

  private static UnsupportedOperationException notYetSupported(
      final String operation) {

    return new UnsupportedOperationException(
        "'%s' is not yet supported by VanillaBP 2! It will be implemented in an upcoming story."
            .formatted(operation));

  }

  /**
   * Builds the exception thrown when {@link #startWorkflow(Object)} is called
   * without an active transaction although the elected adapter requires one. The
   * message guides the developer to the fix.
   *
   * @return The exception to be thrown by the platform bean
   */
  protected static IllegalStateException newMissingTransactionException() {

    return new IllegalStateException(
        """
            No transaction is active! Starting a workflow persists the workflow aggregate and \
            therefore has to run within a transaction: annotate the service method calling \
            'startWorkflow' with @Transactional \
            (org.springframework.transaction.annotation.Transactional on Spring Boot, \
            jakarta.transaction.Transactional on Quarkus).""");

  }

  /**
   * Builds the exception thrown when {@link #sendSignal(String)} is called without
   * an active transaction. A broadcast needs one for a different reason than a
   * workflow start does, so it says so itself.
   *
   * @return The exception to be thrown by the platform bean
   */
  protected static IllegalStateException newMissingTransactionExceptionForSignal() {

    return new IllegalStateException(
        """
            No transaction is active! Broadcasting a signal has to run within a transaction: an \
            embedded BPMS broadcasts inside it (so a rollback takes the broadcast with it), and for \
            a remote BPMS the outbox entry carrying the broadcast rides it. Annotate the service \
            method calling 'sendSignal' with @Transactional \
            (org.springframework.transaction.annotation.Transactional on Spring Boot, \
            jakarta.transaction.Transactional on Quarkus).""");

  }

  @Override
  public A completeUserTask(
      final A workflowAggregate,
      final String taskId) {

    throw notYetSupported("completeUserTask");

  }

  @Override
  public A cancelUserTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    throw notYetSupported("cancelUserTask");

  }

  @Override
  public A completeTask(
      final A workflowAggregate,
      final String taskId) {

    throw notYetSupported("completeTask");

  }

  @Override
  public A cancelTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    throw notYetSupported("cancelTask");

  }

}
