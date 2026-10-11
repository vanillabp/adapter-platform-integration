package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.spi.process.ProcessService;

/**
 * The base of the {@link ProcessService} beans of the platform integrations, which does not
 * depend on the platform. It holds the messages which both platforms would otherwise word
 * twice.
 * <p>
 * It used to have stubs for the operations which VanillaBP 2 did not implement yet. They
 * threw an {@link UnsupportedOperationException} which said so, and were not a silent
 * no-op. There are none left, and the stubs are gone with them. Every operation of
 * {@link ProcessService} is now abstract here as well. So a platform bean which forgets one
 * does not compile. That is a better guard than a message which promises the operation for
 * later. Where a single BPMS cannot do something, its adapter answers, and names itself
 * (see {@code MigratableProcessService}).
 *
 * @param <A> The workflow-aggregate-class
 */
public abstract class ProcessServiceBase<A> implements ProcessService<A> {

  /**
   * Called by the platform bean which extends this class, one for each workflow aggregate
   * class which the application declares. There is no state to hand over here. What a process
   * service needs to know is in the {@link MigrationProcessService} which the platform bean
   * holds.
   */
  protected ProcessServiceBase() {

  }

  /**
   * Builds the exception which is thrown when {@link #startWorkflow(Object)} is called
   * without an active transaction, although the elected adapter requires one. The message
   * guides the developer to the fix.
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
   * Builds the exception which is thrown when {@link #sendSignal(String)} is called without
   * an active transaction. A broadcast needs one for a different reason than a workflow start.
   * So it says so itself.
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
