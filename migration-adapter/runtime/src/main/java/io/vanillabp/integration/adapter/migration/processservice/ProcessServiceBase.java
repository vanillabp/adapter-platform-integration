package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.spi.process.ProcessService;

/**
 * Platform-neutral base of the platform integrations' {@link ProcessService} beans:
 * the messages both platforms would otherwise word twice.
 * <p>
 * It carried stubs for the operations VanillaBP 2 did not implement yet, which threw
 * an {@link UnsupportedOperationException} saying so rather than being a silent
 * no-op. There are none left, and the stubs are gone with them: every operation of
 * {@link ProcessService} is now abstract here as well, so a platform bean which
 * forgets one does not compile - which is a better guard than a message promising the
 * operation for later. What a single BPMS cannot do is answered by its adapter, naming
 * that adapter (see {@code MigratableProcessService}).
 *
 * @param <A> The workflow-aggregate-class
 */
public abstract class ProcessServiceBase<A> implements ProcessService<A> {

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

}
